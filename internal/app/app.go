// Package app wires storage, scanning, background jobs and the HTTP API.
package app

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"maps"
	"os"
	"os/exec"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/robfig/cron/v3"

	"vvideo/internal/config"
	"vvideo/internal/jobs"
	"vvideo/internal/library"
	"vvideo/internal/probe"
	"vvideo/internal/scanner"
	"vvideo/internal/storage"
	"vvideo/internal/streaming"
	"vvideo/internal/thumbnail"
)

const (
	// groupSettle is how long a directory revision must stay unchanged before
	// its album anchors are rebuilt, so bursts of probe results coalesce.
	groupSettle = 700 * time.Millisecond
	groupRetry  = time.Minute
	// sortPublishInterval batches capture-time reorders from probes, so a
	// large import changes each directory's revision at most this often.
	sortPublishInterval = 20 * time.Second
)

type App struct {
	Config     config.Config
	Store      *storage.Store
	Index      *library.Index
	Scanner    *scanner.Scanner
	Queue      *jobs.Queue
	Thumbnails *thumbnail.Engine
	Priority   *jobs.Priority
	Stream     *streaming.Handler

	scanRequests         chan bool // value is the force flag
	wg                   sync.WaitGroup
	cron                 *cron.Cron
	probeNS, thumbnailNS atomic.Int64
}

// New checks external tools, opens the database and recovers interrupted work.
// It does not start background goroutines; see Start.
func New(ctx context.Context, c config.Config) (*App, error) {
	if err := checkMediaTools(ctx); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(c.Cache.Path, 0700); err != nil {
		return nil, fmt.Errorf("thumbnail cache: %w", err)
	}
	s, err := storage.Open(ctx, c.Database())
	if err != nil {
		return nil, err
	}
	index := &library.Index{Store: s}
	if err = index.Register(ctx, c); err != nil {
		s.Close()
		return nil, fmt.Errorf("register media libraries: %w", err)
	}
	if err = index.Recover(ctx); err != nil {
		s.Close()
		return nil, fmt.Errorf("recover interrupted index work: %w", err)
	}
	priority := jobs.NewPriority()
	queue := &jobs.Queue{Store: s, Index: index, Now: time.Now}
	return &App{
		Config:       c,
		Store:        s,
		Index:        index,
		Priority:     priority,
		Queue:        queue,
		Thumbnails:   thumbnail.New(c, queue),
		Scanner:      &scanner.Scanner{Config: c, Index: index, Priority: priority},
		Stream:       streaming.New(index, priority),
		scanRequests: make(chan bool, 1),
	}, nil
}

// checkMediaTools fails startup with an actionable message instead of letting
// every probe and cover job fail later.
func checkMediaTools(ctx context.Context) error {
	var missing []string
	for _, tool := range []string{"ffmpeg", "ffprobe"} {
		if _, err := exec.LookPath(tool); err != nil {
			missing = append(missing, tool)
		}
	}
	if len(missing) > 0 {
		return fmt.Errorf("%s not found in PATH; install FFmpeg (it provides ffmpeg and ffprobe) and restart", strings.Join(missing, " and "))
	}
	return thumbnail.CheckEncoder(ctx)
}

// Trigger requests a scan. Requests made while one is already pending merge.
func (a *App) Trigger(force bool) {
	select {
	case a.scanRequests <- force:
	default:
	}
}

// Start launches background work and schedules an initial scan. Everything
// stops when ctx is cancelled; Close waits for it.
func (a *App) Start(ctx context.Context) error {
	if a.Config.Scan.Cron != "" {
		schedule, err := config.ParseCron(a.Config.Scan.Cron)
		if err != nil {
			return fmt.Errorf("scan.cron: %w", err)
		}
		a.cron = cron.New()
		a.cron.Schedule(schedule, cron.FuncJob(func() {
			// A running or paused scan never accumulates cron work.
			if !a.Scanner.Running() {
				a.Trigger(false)
			}
		}))
		zone, _ := time.Now().Zone()
		// Without CRON_TZ= the expression uses the process's local time zone.
		slog.Info("scan_scheduled", "cron", a.Config.Scan.Cron, "local_zone", zone, "next", schedule.Next(time.Now()).Format(time.RFC3339))
	}
	a.wg.Go(func() { a.scanLoop(ctx) })
	a.Trigger(false)
	a.wg.Go(func() {
		if err := a.Thumbnails.Reconcile(ctx, a.Priority.Wait); err != nil && ctx.Err() == nil {
			slog.Warn("thumbnail_reconcile_failed", "error", err.Error())
		}
	})
	a.wg.Go(func() { a.groupLoop(ctx) })
	a.wg.Go(func() { a.sortLoop(ctx) })
	prober := &probe.Worker{Queue: a.Queue, Parameters: a.Thumbnails.Parameters, Now: time.Now, Rescan: func() { a.Trigger(false) }}
	for range a.Config.Scan.ProbeParallel {
		a.wg.Go(func() { a.Queue.Worker(ctx, "probe", a.Priority, timed(&a.probeNS, prober.Run)) })
	}
	for range a.Config.Cache.Parallel {
		a.wg.Go(func() { a.Queue.Worker(ctx, "thumbnail", a.Priority, timed(&a.thumbnailNS, a.Thumbnails.Run)) })
	}
	if a.cron != nil {
		a.cron.Start()
	}
	return nil
}

// Close waits for background work to stop and closes the database. Cancel the
// context passed to Start first.
func (a *App) Close() error {
	if a.cron != nil {
		<-a.cron.Stop().Done()
	}
	a.wg.Wait()
	return a.Store.Close()
}

func (a *App) scanLoop(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case force := <-a.scanRequests:
			if err := a.Scanner.Run(ctx, force); err != nil && ctx.Err() == nil {
				slog.Warn("scan_failed", "error", err.Error(), "outcome", "previous index retained")
			}
		}
	}
}

// sortLoop publishes capture times found by probes while no media is playing.
func (a *App) sortLoop(ctx context.Context) {
	tick := time.NewTicker(sortPublishInterval)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
		if !a.Priority.Ready() {
			continue
		}
		if _, err := a.Index.PublishProbedTimes(ctx); err != nil && ctx.Err() == nil {
			slog.Warn("sort_publish_failed", "error", err.Error())
		}
	}
}

// groupLoop rebuilds ten-item album anchors for directories whose revision
// changed, while no media is playing. Directories take turns, and one that
// fails waits groupRetry so it can neither starve the others nor flood the log.
func (a *App) groupLoop(ctx context.Context) {
	tick := time.NewTicker(groupSettle)
	defer tick.Stop()
	var after int64
	retryAt := map[int64]time.Time{}
	var lastQueryWarning time.Time
	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
		if !a.Priority.Ready() {
			continue
		}
		id, err := a.Index.DirtyDirectory(ctx, after)
		if err != nil {
			if !errors.Is(err, sql.ErrNoRows) && ctx.Err() == nil && time.Since(lastQueryWarning) >= groupRetry {
				lastQueryWarning = time.Now()
				slog.Warn("album_groups_failed", "error", err.Error())
			}
			continue
		}
		after = id
		now := time.Now()
		maps.DeleteFunc(retryAt, func(_ int64, at time.Time) bool { return !now.Before(at) })
		if _, waiting := retryAt[id]; waiting {
			continue
		}
		err = a.rebuildGroups(ctx, id)
		if err != nil && ctx.Err() == nil && !errors.Is(err, library.ErrConflict) {
			retryAt[id] = now.Add(groupRetry)
			slog.Warn("album_groups_failed", "directory_id", id, "error", err.Error())
		}
	}
}

// rebuildGroups waits for the directory revision to settle, then rebuilds.
func (a *App) rebuildGroups(ctx context.Context, id int64) error {
	before, err := a.Index.Directory(ctx, id)
	if err != nil {
		return err
	}
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-time.After(groupSettle):
	}
	after, err := a.Index.Directory(ctx, id)
	if err != nil || after.Revision != before.Revision {
		return err
	}
	return a.Index.RebuildGroups(ctx, id, a.Priority.Wait)
}

// timed adds each job's duration to total for the status endpoint.
func timed(total *atomic.Int64, run func(context.Context, jobs.Job) error) func(context.Context, jobs.Job) error {
	return func(ctx context.Context, j jobs.Job) error {
		start := time.Now()
		defer func() { total.Add(time.Since(start).Nanoseconds()) }()
		return run(ctx, j)
	}
}
