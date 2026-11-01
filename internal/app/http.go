package app

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
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

// Handler returns the health check and the API: /healthz and /api/info need
// no token, every other /api/ route requires the bearer token.
func (a *App) Handler() http.Handler {
	api := http.NewServeMux()
	api.HandleFunc("GET /api/libraries", a.libraries)
	api.HandleFunc("GET /api/directories/{id}/path", a.directoryPath)
	api.HandleFunc("GET /api/directories/{id}/children", a.directoryChildren)
	api.HandleFunc("GET /api/directories/{id}/media", a.directoryMedia)
	api.HandleFunc("GET /api/directories/{id}/search", a.directorySearch)
	api.HandleFunc("GET /api/media/{id}", a.media)
	api.Handle("GET /api/media/{id}/bytes", a.Stream)
	api.HandleFunc("GET /api/media/{id}/index", a.indexHints)
	api.HandleFunc("GET /api/media/{id}/thumbnail", a.thumbnail)
	api.HandleFunc("POST /api/scans", a.scan)
	api.HandleFunc("GET /api/status", a.status)
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})
	mux.HandleFunc("GET /api/info", a.info)
	mux.Handle("/", a.authenticate(api))
	return a.middleware(mux)
}

// log returns the service logger; an App built without one discards.
func (a *App) log() *slog.Logger {
	if a.logger == nil {
		return slog.New(slog.DiscardHandler)
	}
	return a.logger
}

func (a *App) info(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"version": a.Version, "capabilities": []string{"index_hints", "search", "thumbnails"}})
}

func (a *App) authenticate(next http.Handler) http.Handler {
	token := a.Config.Auth.TokenDigest
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		provided, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		digest := sha256.Sum256([]byte(provided))
		if !ok || subtle.ConstantTimeCompare(digest[:], token[:]) != 1 {
			w.Header().Set("WWW-Authenticate", "Bearer")
			writeError(w, r, unauthenticated)
			return
		}
		next.ServeHTTP(w, r)
	})
}

var requestIDPattern = regexp.MustCompile(`^[A-Za-z0-9-]{1,64}$`)

type loggerKey struct{}

// requestLogger returns the logger carrying the request ID.
func requestLogger(r *http.Request) *slog.Logger {
	if logger, ok := r.Context().Value(loggerKey{}).(*slog.Logger); ok {
		return logger
	}
	return slog.New(slog.DiscardHandler)
}

// middleware is the outermost handler: request ID, panic recovery, security
// headers and the JSON form of the router's own 404 / 405 responses.
func (a *App) middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requestID := r.Header.Get("X-Request-ID")
		if !requestIDPattern.MatchString(requestID) {
			requestID = rand.Text()
		}
		w.Header().Set("X-Request-ID", requestID)
		logger := a.log().With("request_id", requestID)
		r = r.WithContext(context.WithValue(r.Context(), loggerKey{}, logger))
		header := w.Header()
		header.Set("X-Content-Type-Options", "nosniff")
		header.Set("X-Frame-Options", "DENY")
		header.Set("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'")
		header.Set("Cache-Control", "no-store")
		tracked := &routerErrorWriter{ResponseWriter: w, request: r}
		defer func() {
			recovered := recover()
			if recovered == nil {
				return
			}
			if recovered == http.ErrAbortHandler {
				panic(recovered)
			}
			logger.Error("http_handler_panic", "method", r.Method, "path", r.URL.Path, "error", fmt.Sprint(recovered))
			if !tracked.wroteHeader {
				writeError(w, r, errors.New("HTTP handler panicked"))
			}
		}()
		if err := validateQuery(r); err != nil {
			writeError(tracked, r, err)
			return
		}
		next.ServeHTTP(tracked, r)
	})
}

// routerErrorWriter replaces the router's plain-text 404 and 405 responses
// with the JSON error body; handlers write JSON errors themselves.
type routerErrorWriter struct {
	http.ResponseWriter
	request     *http.Request
	wroteHeader bool
	replaced    bool
}

func (w *routerErrorWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (w *routerErrorWriter) WriteHeader(status int) {
	if w.wroteHeader {
		return
	}
	w.wroteHeader = true
	plainText := strings.HasPrefix(w.Header().Get("Content-Type"), "text/plain")
	if plainText && status >= http.StatusBadRequest {
		w.replaced = true
		w.Header().Del("Content-Length")
		writeError(w.ResponseWriter, w.request, routerFailure(status))
		return
	}
	w.ResponseWriter.WriteHeader(status)
}

func (w *routerErrorWriter) Write(body []byte) (int, error) {
	if !w.wroteHeader {
		w.WriteHeader(http.StatusOK)
	}
	if w.replaced {
		return len(body), nil
	}
	return w.ResponseWriter.Write(body)
}

func (a *App) libraries(w http.ResponseWriter, r *http.Request) {
	v, err := a.Index.Libraries(r.Context())
	respond(w, r, responseResult{value: listDTO[libraryDTO]{Items: mapAll(v, libraryWire)}, err: err})
}

func (a *App) directoryPath(w http.ResponseWriter, r *http.Request) {
	id, err := directoryID(r)
	if err != nil {
		writeError(w, r, err)
		return
	}
	v, err := a.Index.Path(r.Context(), id)
	respond(w, r, responseResult{value: listDTO[directoryDTO]{Items: mapAll(v, directoryWire)}, err: err})
}

func (a *App) directoryChildren(w http.ResponseWriter, r *http.Request) {
	id, err := directoryID(r)
	if err != nil {
		writeError(w, r, err)
		return
	}
	q := r.URL.Query()
	v, err := a.Index.Children(r.Context(), id, q.Get("cursor"), q.Get("previous") == "true")
	respond(w, r, responseResult{value: childrenPageWire(v), err: err})
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
			writeError(w, r, invalidParameter("limit", "out_of_range"))
			return
		}
	}
	v, err := a.Index.List(r.Context(), id, o)
	respond(w, r, responseResult{value: mediaPageWire(v), err: err})
}

func (a *App) directorySearch(w http.ResponseWriter, r *http.Request) {
	id, err := directoryID(r)
	if err != nil {
		writeError(w, r, err)
		return
	}
	v, err := a.Index.Search(r.Context(), id, r.URL.Query().Get("q"))
	respond(w, r, responseResult{value: searchWire(v), err: err})
}

func (a *App) media(w http.ResponseWriter, r *http.Request) {
	v, err := a.Index.Media(r.Context(), r.PathValue("id"))
	respond(w, r, responseResult{value: mediaWire(v), err: err})
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
	if r.URL.Query().Get("version") == "" {
		return item, invalidParameter("version", "required")
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
	writeJSON(w, http.StatusOK, hintsWire(hints))
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
		writeError(w, r, thumbnailPending)
		return
	case "unsupported", "failed": // permanent for this version
		writeError(w, r, thumbnailUnavailable)
		return
	}
	f, release, err := a.Thumbnails.Open(item)
	if err != nil {
		f, release, err = a.generateThumbnail(r.Context(), item)
	}
	if errors.Is(err, context.DeadlineExceeded) {
		w.Header().Set("Retry-After", "2")
		writeError(w, r, thumbnailPending)
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
	if err != nil || id <= 0 {
		return 0, invalidParameter("id", "out_of_range")
	}
	return id, nil
}

// respond writes v as JSON, or maps err to an error response.
type responseResult struct {
	value any
	err   error
}

func respond(w http.ResponseWriter, r *http.Request, result responseResult) {
	v, err := result.value, result.err
	if err != nil {
		writeError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, v)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	// The peer may disconnect after headers; the response cannot be replaced.
	// File and directory names are raw bytes that need not be valid UTF-8;
	// they are shown with U+FFFD rather than breaking the whole response.
	_ = json.MarshalWrite(w, v, jsontext.AllowInvalidUTF8(true))
}
