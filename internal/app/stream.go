package app

import (
	"errors"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"vvideo/internal/jobs"
	"vvideo/internal/library"
	"vvideo/internal/mediafs"
)

const (
	maxStreams       = 8
	writeIdleTimeout = 60 * time.Second
)

// StreamHandler serves original media bytes with Range support. It rejects bytes
// whose file version no longer matches the published index.
type StreamHandler struct {
	Index    *library.Index
	Priority *jobs.Priority
	slots    chan struct{}
}

// NewStreamHandler returns a StreamHandler that reports each transfer to p.
func NewStreamHandler(index *library.Index, p *jobs.Priority) *StreamHandler {
	return &StreamHandler{Index: index, Priority: p, slots: make(chan struct{}, maxStreams)}
}

// ServeHTTP answers GET /api/media/{id}/bytes?version=. A version that no longer
// matches the index gets 409; a file rewritten mid-response aborts the
// connection so the client never mixes old and new bytes.
func (h *StreamHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	select {
	case h.slots <- struct{}{}:
		defer func() { <-h.slots }()
	default:
		w.Header().Set("Retry-After", "1")
		writeError(w, r, mediaBusy)
		return
	}
	end := h.Priority.Begin()
	defer end()
	e, err := h.Index.Media(r.Context(), r.PathValue("id"))
	if err != nil {
		writeError(w, r, err)
		return
	}
	requested := r.URL.Query().Get("version")
	if requested == "" {
		writeError(w, r, invalidParameter("version", "required"))
		return
	}
	if requested != e.Version {
		writeError(w, r, library.ErrConflict)
		return
	}
	f, _, err := h.Index.OpenMedia(r.Context(), e)
	if err != nil {
		if errors.Is(err, library.ErrConflict) {
			writeError(w, r, library.ErrConflict)
		} else {
			writeError(w, r, err)
		}
		return
	}
	defer f.Close()
	w.Header().Set("ETag", `"`+e.Version+`"`)
	w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
	contentType := mime.TypeByExtension(filepath.Ext(e.Name))
	if contentType != "" {
		w.Header().Set("Content-Type", contentType)
	}
	reader := &versionReader{f: f, fingerprint: e.Fingerprint, checkCanceled: r.Context().Err}
	http.ServeContent(&progressWriter{ResponseWriter: w, controller: http.NewResponseController(w)}, r, e.Name, time.Unix(0, e.Mtime), reader)
	if reader.changed {
		panic(http.ErrAbortHandler)
	}
}

// versionReader re-checks the file's fingerprint around every read.
type versionReader struct {
	f             *os.File
	fingerprint   string
	checkCanceled func() error
	changed       bool
}

func (v *versionReader) Seek(offset int64, whence int) (int64, error) {
	return v.f.Seek(offset, whence)
}

func (v *versionReader) Read(b []byte) (int, error) {
	if err := v.checkCanceled(); err != nil {
		return 0, err
	}
	before, err := v.f.Stat()
	if err != nil {
		return 0, err
	}
	if mediafs.Fingerprint(before) != v.fingerprint {
		v.changed = true
		return 0, library.ErrConflict
	}
	n, err := v.f.Read(b)
	after, statErr := v.f.Stat()
	if statErr != nil || mediafs.Fingerprint(after) != v.fingerprint {
		v.changed = true
		return 0, library.ErrConflict
	}
	return n, err
}

// progressWriter extends the write deadline after each write.
type progressWriter struct {
	http.ResponseWriter
	controller *http.ResponseController
}

func (w *progressWriter) Write(b []byte) (int, error) {
	if err := w.controller.SetWriteDeadline(time.Now().Add(writeIdleTimeout)); err != nil && !errors.Is(err, http.ErrNotSupported) {
		return 0, err
	}
	return w.ResponseWriter.Write(b)
}

func (w *progressWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }
