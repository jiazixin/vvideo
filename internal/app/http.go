package app

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"vvideo/internal/library"
	"vvideo/internal/streaming"
)

const (
	// thumbnailWait bounds how long a request waits for an on-demand cover
	// before answering 503 with Retry-After.
	thumbnailWait = 2 * time.Second
	thumbnailPoll = 100 * time.Millisecond
)

// Handler returns the authenticated /v1 API.
func (a *App) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/libraries", a.libraries)
	mux.HandleFunc("GET /v1/directories/{id}/path", a.directoryPath)
	mux.HandleFunc("GET /v1/directories/{id}/children", a.directoryChildren)
	mux.HandleFunc("GET /v1/directories/{id}/media", a.directoryMedia)
	mux.HandleFunc("GET /v1/directories/{id}/search", a.directorySearch)
	mux.HandleFunc("GET /v1/media/{id}", a.media)
	mux.Handle("GET /v1/media/{id}/bytes", a.Stream)
	mux.HandleFunc("GET /v1/media/{id}/index", a.indexHints)
	mux.HandleFunc("GET /v1/media/{id}/thumbnail", a.thumbnail)
	mux.HandleFunc("POST /v1/scan", a.scan)
	mux.HandleFunc("GET /v1/status", a.status)
	// Unknown paths and methods answer in the same JSON shape as other errors.
	mux.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusNotFound, errorBody("unavailable"))
	})
	return a.authenticate(mux)
}

func (a *App) authenticate(next http.Handler) http.Handler {
	token := []byte(a.Config.Auth.Token)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		provided, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok || subtle.ConstantTimeCompare([]byte(provided), token) != 1 {
			w.Header().Set("WWW-Authenticate", "Bearer")
			writeJSON(w, http.StatusUnauthorized, errorBody("unauthorized"))
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (a *App) libraries(w http.ResponseWriter, r *http.Request) {
	v, err := a.Index.Libraries(r.Context())
	respond(w, r, v, err)
}

func (a *App) directoryPath(w http.ResponseWriter, r *http.Request) {
	id, err := directoryID(r)
	if err != nil {
		writeError(w, r, err)
		return
	}
	v, err := a.Index.Path(r.Context(), id)
	respond(w, r, v, err)
}

func (a *App) directoryChildren(w http.ResponseWriter, r *http.Request) {
	id, err := directoryID(r)
	if err != nil {
		writeError(w, r, err)
		return
	}
	q := r.URL.Query()
	v, err := a.Index.Children(r.Context(), id, q.Get("cursor"), q.Get("previous") == "true")
	respond(w, r, v, err)
}

func (a *App) directoryMedia(w http.ResponseWriter, r *http.Request) {
	id, err := directoryID(r)
	if err != nil {
		writeError(w, r, err)
		return
	}
	q := r.URL.Query()
	o := library.ListOptions{
		Sort:      q.Get("sort"),
		Direction: q.Get("direction"),
		Kind:      q.Get("kind"),
		Cursor:    q.Get("cursor"),
		Previous:  q.Get("previous") == "true",
		Anchor:    q.Get("anchor"),
		Fallback:  q.Get("fallback"),
	}
	if q.Has("limit") {
		if o.Limit, err = strconv.Atoi(q.Get("limit")); err != nil || o.Limit < 1 {
			writeError(w, r, fmt.Errorf("%w: limit must be a positive integer", library.ErrInvalid))
			return
		}
	}
	v, err := a.Index.List(r.Context(), id, o)
	respond(w, r, v, err)
}

func (a *App) directorySearch(w http.ResponseWriter, r *http.Request) {
	id, err := directoryID(r)
	if err != nil {
		writeError(w, r, err)
		return
	}
	v, err := a.Index.Search(r.Context(), id, r.URL.Query().Get("q"))
	respond(w, r, v, err)
}

func (a *App) media(w http.ResponseWriter, r *http.Request) {
	v, err := a.Index.Media(r.Context(), r.PathValue("id"))
	respond(w, r, v, err)
}

func (a *App) scan(w http.ResponseWriter, r *http.Request) {
	a.Trigger(r.URL.Query().Get("force") == "true")
	writeJSON(w, http.StatusAccepted, map[string]string{"status": "scheduled"})
}

type statusResponse struct {
	Scanning              bool             `json:"scanning"`
	ThumbnailBytes        int64            `json:"thumbnail_bytes"`
	ScanPages             int64            `json:"scan_pages"`
	ScanFiles             int64            `json:"scan_files"`
	ScanErrors            int64            `json:"scan_errors"`
	WriterCommits         int64            `json:"writer_commits"`
	VersionRecordsWritten int64            `json:"version_records_written"`
	MaxTransactionNS      int64            `json:"max_transaction_ns"`
	PhaseNS               map[string]int64 `json:"phase_ns"`
}

func (a *App) status(w http.ResponseWriter, r *http.Request) {
	thumbnailBytes, err := a.Thumbnails.Bytes(r.Context())
	if err != nil {
		writeError(w, r, err)
		return
	}
	scan, store := &a.Scanner.Metrics, &a.Store.Metrics
	writeJSON(w, http.StatusOK, statusResponse{
		Scanning:              a.Scanner.Running(),
		ThumbnailBytes:        thumbnailBytes,
		ScanPages:             scan.Pages.Load(),
		ScanFiles:             scan.Files.Load(),
		ScanErrors:            scan.Errors.Load(),
		WriterCommits:         store.Commits.Load(),
		VersionRecordsWritten: store.Rows.Load(),
		MaxTransactionNS:      store.MaxTransactionNS.Load(),
		PhaseNS: map[string]int64{
			"readdir":          scan.ReadDirNS.Load(),
			"attributes":       scan.StatNS.Load(),
			"stage":            scan.StageNS.Load(),
			"compare":          scan.CompareNS.Load(),
			"build":            scan.BuildNS.Load(),
			"publish":          scan.PublishNS.Load(),
			"cleanup":          scan.CleanupNS.Load(),
			"writer_wait":      store.WaitNS.Load(),
			"writer_execution": store.ExecutionNS.Load(),
			"commit":           store.CommitNS.Load(),
			"probe":            a.probeNS.Load(),
			"thumbnail":        a.thumbnailNS.Load(),
		},
	})
}

// requestedVersion loads the media item and requires the client's ?version=
// to match the published one.
func (a *App) requestedVersion(r *http.Request) (library.Entry, error) {
	item, err := a.Index.Media(r.Context(), r.PathValue("id"))
	if err != nil {
		return item, err
	}
	if r.URL.Query().Get("version") != item.Version {
		return item, library.ErrConflict
	}
	return item, nil
}

func (a *App) indexHints(w http.ResponseWriter, r *http.Request) {
	item, err := a.requestedVersion(r)
	if err != nil {
		writeError(w, r, err)
		return
	}
	hints := streaming.Hints{Version: item.Version, Size: item.Size, Ranges: []streaming.Range{}}
	var parse func(context.Context, io.ReaderAt, int64) ([]streaming.Range, error)
	switch strings.ToLower(filepath.Ext(item.Name)) {
	case ".mp4", ".mov", ".m4v":
		parse = streaming.MP4Ranges
	case ".mkv", ".webm":
		parse = streaming.MatroskaRanges
	case ".ts":
		hints.Ranges = streaming.TSRanges(item.Size)
	}
	if parse != nil {
		f, validate, err := a.Index.OpenMedia(r.Context(), item)
		if err != nil {
			writeError(w, r, err)
			return
		}
		defer f.Close()
		// Hints are optional: a malformed or oversized index falls back to
		// ordinary Range playback with no ranges.
		if ranges, err := parse(r.Context(), f, item.Size); err == nil && validate() == nil {
			hints.Ranges = ranges
		}
	}
	writeJSON(w, http.StatusOK, hints)
}

func (a *App) thumbnail(w http.ResponseWriter, r *http.Request) {
	item, err := a.requestedVersion(r)
	if err != nil {
		writeError(w, r, err)
		return
	}
	switch item.Status {
	case "discovered": // metadata is still being probed
		w.Header().Set("Retry-After", "2")
		writeJSON(w, http.StatusNotFound, errorBody("thumbnail_pending"))
		return
	case "unsupported", "failed": // permanent for this version
		writeJSON(w, http.StatusNotFound, errorBody("thumbnail_unavailable"))
		return
	}
	f, release, err := a.Thumbnails.Open(item)
	if err != nil {
		f, release, err = a.generateThumbnail(r.Context(), item)
	}
	if errors.Is(err, context.DeadlineExceeded) {
		w.Header().Set("Retry-After", "2")
		writeJSON(w, http.StatusServiceUnavailable, errorBody("thumbnail_pending"))
		return
	}
	if err != nil {
		writeError(w, r, err)
		return
	}
	defer f.Close()
	defer release()
	info, err := f.Stat()
	if err != nil {
		writeError(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "image/webp")
	w.Header().Set("ETag", `"`+a.Thumbnails.Key(item)+`"`)
	w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
	http.ServeContent(w, r, "thumbnail.webp", info.ModTime(), f)
	ctx, cancel := context.WithTimeout(r.Context(), time.Second)
	defer cancel()
	a.Thumbnails.Touch(ctx, item)
}

// generateThumbnail queues a foreground cover job and waits up to
// thumbnailWait for it, returning context.DeadlineExceeded if it is not ready.
func (a *App) generateThumbnail(ctx context.Context, item library.Entry) (*os.File, func(), error) {
	if err := a.Queue.EnqueueThumbnail(ctx, item, a.Thumbnails.Parameters, true); err != nil {
		return nil, nil, err
	}
	ctx, cancel := context.WithTimeout(ctx, thumbnailWait)
	defer cancel()
	poll := time.NewTicker(thumbnailPoll)
	defer poll.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil, nil, ctx.Err()
		case <-poll.C:
			if f, release, err := a.Thumbnails.Open(item); err == nil {
				return f, release, nil
			}
		}
	}
}

func directoryID(r *http.Request) (int64, error) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		return 0, fmt.Errorf("%w: directory id must be an integer", library.ErrInvalid)
	}
	return id, nil
}

// respond writes v as JSON, or maps err to an error response.
func respond(w http.ResponseWriter, r *http.Request, v any, err error) {
	if err != nil {
		writeError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, v)
}

func errorBody(code string) map[string]string { return map[string]string{"error": code} }

// writeError maps domain errors to stable status codes and error codes.
// Unexpected errors are logged and reported as 500 without internal detail.
func writeError(w http.ResponseWriter, r *http.Request, err error) {
	if r.Context().Err() != nil {
		return // the client is gone
	}
	status, code := http.StatusInternalServerError, "internal_error"
	switch {
	case errors.Is(err, library.ErrInvalid):
		status, code = http.StatusBadRequest, "invalid_request"
	case errors.Is(err, library.ErrConflict):
		status, code = http.StatusConflict, "revision_changed"
	case errors.Is(err, library.ErrUnavailable), errors.Is(err, sql.ErrNoRows), errors.Is(err, fs.ErrNotExist):
		status, code = http.StatusNotFound, "unavailable"
	default:
		slog.Error("http_request_failed", "route", r.Pattern, "error", err.Error())
	}
	writeJSON(w, status, errorBody(code))
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	// File and directory names are raw bytes that need not be valid UTF-8;
	// they are shown with U+FFFD rather than breaking the whole response.
	if err := json.MarshalWrite(w, v, jsontext.AllowInvalidUTF8(true)); err != nil {
		slog.Warn("http_response_failed", "error", err.Error())
	}
}
