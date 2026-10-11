package app

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"encoding/json/v2"
	"errors"
	"github.com/robfig/cron/v3"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"vvideo/internal/config"
	"vvideo/internal/jobs"
	"vvideo/internal/library"
	"vvideo/internal/probe"
	"vvideo/internal/scanner"
	"vvideo/internal/storage"
	"vvideo/internal/streaming"
	"vvideo/internal/thumbnail"
)

type App struct {
	Config               config.Config
	Store                *storage.Store
	Index                *library.Index
	Scanner              *scanner.Scanner
	Queue                *jobs.Queue
	Thumbnails           *thumbnail.Engine
	Priority             *jobs.Priority
	Stream               *streaming.Handler
	wake                 chan bool
	wg                   sync.WaitGroup
	cron                 *cron.Cron
	probeNS, thumbnailNS atomic.Int64
}

func New(ctx context.Context, c config.Config) (*App, error) {
	for _, tool := range []string{"ffmpeg", "ffprobe"} {
		if _, err := exec.LookPath(tool); err != nil {
			return nil, errors.New("required media tool is not on PATH")
		}
	}
	s, err := storage.Open(ctx, c.Storage)
	if err != nil {
		return nil, err
	}
	index := &library.Index{Store: s}
	if err = index.Register(ctx, c); err != nil {
		s.Close()
		return nil, err
	}
	if err = index.Recover(ctx); err != nil {
		s.Close()
		return nil, err
	}
	if err = os.MkdirAll(c.Cache.Path, 0700); err != nil {
		s.Close()
		return nil, err
	}
	p := jobs.NewPriority()
	q := &jobs.Queue{Store: s, Index: index, Now: time.Now}
	a := &App{Config: c, Store: s, Index: index, Priority: p, Queue: q, Thumbnails: thumbnail.New(c, q), Scanner: &scanner.Scanner{Config: c, Index: index, Priority: p}, Stream: streaming.New(index, p), wake: make(chan bool, 1)}
	return a, nil
}
func (a *App) Trigger(force bool) {
	select {
	case a.wake <- force:
	default:
	}
}
func (a *App) Start(ctx context.Context) error {
	a.wg.Go(func() {
		for {
			select {
			case <-ctx.Done():
				return
			case force := <-a.wake:
				if err := a.Scanner.Run(ctx, force); err != nil && ctx.Err() == nil {
					slog.Warn("scan_failed", "reason", "old_index_retained")
				}
			}
		}
	})
	a.Trigger(false)
	a.wg.Go(func() {
		if err := a.Thumbnails.Reconcile(ctx, a.Priority.Wait); err != nil && ctx.Err() == nil {
			slog.Warn("cache_reconciliation_failed")
		}
	})
	a.wg.Go(func() {
		for {
			select {
			case <-ctx.Done():
				return
			case <-time.After(700 * time.Millisecond):
			}
			if !a.Priority.Ready() {
				continue
			}
			id, err := a.Index.DirtyDirectory(ctx)
			if err != nil {
				continue
			}
			d, err := a.Index.Directory(ctx, id)
			if err != nil {
				continue
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(700 * time.Millisecond):
			}
			now, err := a.Index.Directory(ctx, id)
			if err != nil || now.Revision != d.Revision {
				continue
			}
			a.Index.RebuildGroups(ctx, id, a.Priority.Wait)
		}
	})
	w := &probe.Worker{Queue: a.Queue, Parameters: a.Thumbnails.Parameters, Now: time.Now, Rescan: func() { a.Trigger(false) }}
	for range 2 {
		a.wg.Go(func() {
			a.Queue.Worker(ctx, "probe", a.Priority, func(ctx context.Context, j jobs.Job) error {
				start := time.Now()
				defer func() { a.probeNS.Add(time.Since(start).Nanoseconds()) }()
				return w.Run(ctx, j)
			})
		})
	}
	for range a.Config.Cache.Parallel {
		a.wg.Go(func() {
			a.Queue.Worker(ctx, "thumbnail", a.Priority, func(ctx context.Context, j jobs.Job) error {
				start := time.Now()
				defer func() { a.thumbnailNS.Add(time.Since(start).Nanoseconds()) }()
				return a.Thumbnails.Run(ctx, j)
			})
		})
	}
	if a.Config.Scan.Cron != "" {
		a.cron = cron.New(cron.WithParser(cron.NewParser(cron.Minute | cron.Hour | cron.Dom | cron.Month | cron.Dow)))
		_, err := a.cron.AddFunc(a.Config.Scan.Cron, func() { // a running/paused scan never accumulates cron work.
			if !a.Scanner.Running() {
				a.Trigger(false)
			}
		})
		if err != nil {
			return err
		}
		a.cron.Start()
	}
	return nil
}
func (a *App) Close() error {
	if a.cron != nil {
		<-a.cron.Stop().Done()
	}
	a.wg.Wait()
	return a.Store.Close()
}
func (a *App) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/libraries", func(w http.ResponseWriter, r *http.Request) {
		v, err := a.Index.Libraries(r.Context())
		respond(w, v, err)
	})
	mux.HandleFunc("GET /v1/directories/{id}/path", func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
		if err == nil {
			err = a.Index.Visible(r.Context(), id)
		}
		if err != nil {
			respond(w, nil, err)
			return
		}
		path := []library.Directory{}
		for id != 0 {
			if len(path) >= 1024 {
				respond(w, nil, library.ErrUnavailable)
				return
			}
			directory, err := a.Index.Directory(r.Context(), id)
			if err != nil {
				respond(w, nil, err)
				return
			}
			path = append(path, directory)
			id = directory.ParentID
		}
		slices.Reverse(path)
		respond(w, path, nil)
	})
	mux.HandleFunc("GET /v1/directories/{id}/children", func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
		if err != nil {
			respond(w, nil, err)
			return
		}
		v, err := a.Index.Children(r.Context(), id, r.URL.Query().Get("cursor"), r.URL.Query().Get("previous") == "true")
		respond(w, v, err)
	})
	mux.HandleFunc("GET /v1/directories/{id}/media", func(w http.ResponseWriter, r *http.Request) {
		id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
		if err != nil {
			respond(w, nil, err)
			return
		}
		q := r.URL.Query()
		limit := 0
		if q.Has("limit") {
			limit, err = strconv.Atoi(q.Get("limit"))
			if err != nil {
				respond(w, nil, err)
				return
			}
		}
		v, err := a.Index.List(r.Context(), id, library.ListOptions{Sort: q.Get("sort"), Direction: q.Get("direction"), Kind: q.Get("kind"), Cursor: q.Get("cursor"), Previous: q.Get("previous") == "true", Anchor: q.Get("anchor"), Fallback: q.Get("fallback"), Limit: limit})
		respond(w, v, err)
	})
	mux.HandleFunc("GET /v1/media/{id}", func(w http.ResponseWriter, r *http.Request) {
		v, err := a.Index.Media(r.Context(), r.PathValue("id"))
		respond(w, v, err)
	})
	mux.Handle("GET /v1/media/{id}/bytes", a.Stream)
	mux.HandleFunc("GET /v1/media/{id}/index", a.indexHints)
	mux.HandleFunc("GET /v1/media/{id}/thumbnail", a.thumbnail)
	mux.HandleFunc("POST /v1/scan", func(w http.ResponseWriter, r *http.Request) {
		a.Trigger(r.URL.Query().Get("force") == "true")
		w.WriteHeader(http.StatusAccepted)
		respond(w, map[string]string{"status": "scheduled"}, nil)
	})
	mux.HandleFunc("GET /v1/status", func(w http.ResponseWriter, r *http.Request) {
		n, err := a.Thumbnails.Bytes(r.Context())
		if err != nil {
			respond(w, nil, err)
			return
		}
		respond(w, map[string]any{"scanning": a.Scanner.Running(), "thumbnail_bytes": n, "scan_pages": a.Scanner.Metrics.Pages.Load(), "scan_files": a.Scanner.Metrics.Files.Load(), "scan_errors": a.Scanner.Metrics.Errors.Load(), "writer_commits": a.Store.Metrics.Commits.Load(), "version_records_written": a.Store.Metrics.Rows.Load(), "max_transaction_ns": a.Store.Metrics.MaxTransactionNS.Load(), "phase_ns": map[string]int64{"readdir": a.Scanner.Metrics.ReadDirNS.Load(), "attributes": a.Scanner.Metrics.StatNS.Load(), "stage": a.Scanner.Metrics.StageNS.Load(), "compare": a.Scanner.Metrics.CompareNS.Load(), "build": a.Scanner.Metrics.BuildNS.Load(), "publish": a.Scanner.Metrics.PublishNS.Load(), "cleanup": a.Scanner.Metrics.CleanupNS.Load(), "writer_wait": a.Store.Metrics.WaitNS.Load(), "writer_execution": a.Store.Metrics.ExecutionNS.Load(), "commit": a.Store.Metrics.CommitNS.Load(), "probe": a.probeNS.Load(), "thumbnail": a.thumbnailNS.Load()}}, nil)
	})
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		provided := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if subtle.ConstantTimeCompare([]byte(provided), []byte(a.Config.Auth.Token)) != 1 || !strings.HasPrefix(r.Header.Get("Authorization"), "Bearer ") {
			w.Header().Set("WWW-Authenticate", "Bearer")
			http.Error(w, "unauthorized", 401)
			return
		}
		mux.ServeHTTP(w, r)
	})
}
func respond(w http.ResponseWriter, v any, err error) {
	w.Header().Set("Content-Type", "application/json")
	if err != nil {
		code := 400
		msg := "invalid_request"
		if errors.Is(err, library.ErrConflict) {
			code = 409
			msg = "revision_changed"
		} else if errors.Is(err, library.ErrUnavailable) || errors.Is(err, sql.ErrNoRows) || errors.Is(err, os.ErrNotExist) {
			code = 404
			msg = "unavailable"
		}
		w.WriteHeader(code)
		v = map[string]string{"error": msg}
	}
	if e := json.MarshalWrite(w, v); e != nil {
		slog.Warn("http_response_failed")
	}
}
func (a *App) indexHints(w http.ResponseWriter, r *http.Request) {
	item, err := a.Index.Media(r.Context(), r.PathValue("id"))
	if err != nil {
		respond(w, nil, err)
		return
	}
	if r.URL.Query().Get("version") != item.Version {
		respond(w, nil, library.ErrConflict)
		return
	}
	h := streaming.Hints{Version: item.Version, Size: item.Size, Ranges: []streaming.Range{}}
	ext := strings.ToLower(filepath.Ext(item.Name))
	if ext == ".mp4" || ext == ".mov" || ext == ".m4v" {
		f, validate, err := a.Index.OpenMedia(r.Context(), item)
		if err != nil {
			respond(w, nil, err)
			return
		}
		defer f.Close()
		ranges, err := streaming.MP4Ranges(r.Context(), f, item.Size)
		if err == nil && validate() == nil {
			h.Ranges = ranges
		}
	}
	respond(w, h, nil)
}
func (a *App) thumbnail(w http.ResponseWriter, r *http.Request) {
	item, err := a.Index.Media(r.Context(), r.PathValue("id"))
	if err != nil {
		respond(w, nil, err)
		return
	}
	if r.URL.Query().Get("version") != item.Version {
		respond(w, nil, library.ErrConflict)
		return
	}
	if item.Status == "discovered" || item.Status == "unsupported" {
		w.Header().Set("Retry-After", "2")
		http.Error(w, "thumbnail_pending_or_unsupported", 404)
		return
	}
	f, release, err := a.Thumbnails.Open(item)
	if err != nil {
		if err = a.Queue.EnqueueThumbnail(r.Context(), item, a.Thumbnails.Parameters, true); err != nil {
			respond(w, nil, err)
			return
		}
		deadline := time.NewTimer(2 * time.Second)
		defer deadline.Stop()
	wait:
		for {
			select {
			case <-r.Context().Done():
				return
			case <-deadline.C:
				break wait
			case <-time.After(100 * time.Millisecond):
				f, release, err = a.Thumbnails.Open(item)
				if err == nil {
					break wait
				}
			}
		}
	}
	if err != nil {
		w.Header().Set("Retry-After", "2")
		http.Error(w, "thumbnail_pending", 503)
		return
	}
	defer f.Close()
	defer release()
	w.Header().Set("Content-Type", "image/webp")
	w.Header().Set("ETag", `"`+a.Thumbnails.Key(item)+`"`)
	w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
	info, err := f.Stat()
	if err != nil {
		respond(w, nil, err)
		return
	}
	http.ServeContent(w, r, "thumbnail.webp", info.ModTime(), f)
	ctx, cancel := context.WithTimeout(r.Context(), time.Second)
	defer cancel()
	a.Thumbnails.Touch(ctx, item)
}
