// Package app wires storage, scanning, background jobs and the HTTP API.
// Construct with New, bind the HTTP listener, then call Start. Drain HTTP before
// Shutdown, which cancels and joins background work before closing storage.
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
	// thumbnailProgressInterval is how often cover progress is logged while
	// covers are being generated; an idle queue logs nothing.
	thumbnailProgressInterval = 5 * time.Minute
)

// App is the running service: the index, its background workers and the HTTP
// API that serves it. lifecycle guards startup and shutdown; the counters are
// atomic and background workers are joined through wg before storage closes.
type App struct {
	logger     *slog.Logger
	Version    string
	Config     config.Config
	Store      *storage.Store
	Index      *library.Index
	Scanner    *scanner.Scanner
	Queue      *jobs.Queue
	Thumbnails *thumbnail.Engine
	Priority   *jobs.Priority
	Stream     *StreamHandler

	scanRequests         chan struct{}
	forceScan            atomic.Bool
	wg                   sync.WaitGroup
	cron                 *cron.Cron
	probeNS, thumbnailNS atomic.Int64
	lifecycle            sync.Mutex
	started, stopping    bool
	cancel               context.CancelFunc
	cleanup              sync.WaitGroup
	stopped              chan struct{}
	closeErr             error // published by closing stopped
}

// New checks external tools, opens the database and recovers interrupted work.
// It does not start background goroutines; see Start.
func New(ctx context.Context, c config.Config, logger *slog.Logger) (*App, error) {
	if logger == nil {
		logger = slog.New(slog.DiscardHandler)
	}
	if err := checkMediaTools(ctx); err != nil {
		return nil, err
	}
	if err := os.MkdirAll(c.Cache.Path, 0o700); err != nil {
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
	priority := jobs.NewPriority(time.Now)
	queue := jobs.NewQueue(index, logger, time.Now)
	thumbnails := thumbnail.New(c, queue)
	thumbnails.Logger = logger
	return &App{
		logger:       logger,
		Config:       c,
		Store:        s,
		Index:        index,
		Priority:     priority,
		Queue:        queue,
		Thumbnails:   thumbnails,
		Scanner:      &scanner.Scanner{Config: c, Index: index, Logger: logger},
		Stream:       NewStreamHandler(index, priority),
		scanRequests: make(chan struct{}, 1),
		stopped:      make(chan struct{}),
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
	if force {
		a.forceScan.Store(true)
	}
	select {
	case a.scanRequests <- struct{}{}:
	default:
	}
}

// Start launches background work and schedules an initial scan. Everything
// stops when ctx is cancelled; Shutdown cancels and waits for it.
func (a *App) Start(ctx context.Context) error {
	a.lifecycle.Lock()
	defer a.lifecycle.Unlock()
	if a.started || a.stopping {
		return errors.New("application already started or stopping")
	}
	if a.Config.Scan.Cron != "" {
		schedule, err := config.ParseCron(a.Config.Scan.Cron)
		if err != nil {
			return fmt.Errorf("scan.cron: %w", err)
		}
		a.cron = cron.New()
		a.cron.Schedule(schedule, cron.FuncJob(func() {
			// A running scan never accumulates cron work.
			if !a.Scanner.Running() {
				a.Trigger(false)
			}
		}))
		zone, _ := time.Now().Zone()
		// Without CRON_TZ= the expression uses the process's local time zone.
		a.logger.Info("scan_scheduled", "cron", a.Config.Scan.Cron, "local_zone", zone, "next", schedule.Next(time.Now()).Format(time.RFC3339))
	}
	ctx, a.cancel = context.WithCancel(ctx)
	a.started = true
	a.wg.Go(func() { a.scanLoop(ctx) })
	a.Trigger(false)
	a.wg.Go(func() {
		if err := a.Thumbnails.Reconcile(ctx); err != nil && ctx.Err() == nil {
			a.logger.Warn("thumbnail_reconcile_failed", "error", err)
		}
	})
	a.wg.Go(func() { a.groupLoop(ctx) })
	a.wg.Go(func() { a.sortLoop(ctx) })
	prober := &probe.Worker{Queue: a.Queue, Parameters: a.Thumbnails.Parameters, Rescan: func() { a.Trigger(false) }}
	for range a.Config.Scan.ProbeParallel {
		a.wg.Go(func() { a.Queue.Worker(ctx, "probe", timed(&a.probeNS, prober.Run)) })
	}
	for range a.Config.Cache.Parallel {
		a.wg.Go(func() { a.Queue.Worker(ctx, "thumbnail", timed(&a.thumbnailNS, a.Thumbnails.Run)) })
	}
	progress := &thumbnail.Progress{Engine: a.Thumbnails, Logger: a.logger}
	a.wg.Go(func() { progress.Run(ctx, thumbnailProgressInterval) })
	if a.cron != nil {
		a.cron.Start()
	}
	return nil
}

// Shutdown cancels background work and closes storage once workers have exited.
// A deadline bounds the caller's wait; cleanup continues without closing storage
// underneath an active worker. Repeated calls wait for the same cleanup result.
func (a *App) Shutdown(ctx context.Context) error {
	a.lifecycle.Lock()
	if !a.stopping {
		a.stopping = true
		if a.cancel != nil {
			a.cancel()
		}
		a.cleanup.Go(func() {
			if a.cron != nil {
				<-a.cron.Stop().Done()
			}
			a.wg.Wait()
			a.closeErr = a.Store.Close()
			close(a.stopped)
		})
	}
	a.lifecycle.Unlock()
	select {
	case <-a.stopped:
		a.cleanup.Wait()
		return a.closeErr
	case <-ctx.Done():
		return fmt.Errorf("wait for application shutdown: %w", ctx.Err())
	}
}

func (a *App) scanLoop(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case <-a.scanRequests:
			if err := a.Scanner.Run(ctx, a.forceScan.Swap(false)); err != nil && ctx.Err() == nil {
				a.logger.Warn("scan_failed", "error", err, "outcome", "previous index retained")
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
			a.logger.Warn("sort_publish_failed", "error", err)
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
				a.logger.Warn("album_groups_failed", "error", err)
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
			a.logger.Warn("album_groups_failed", "directory_id", id, "error", err)
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
