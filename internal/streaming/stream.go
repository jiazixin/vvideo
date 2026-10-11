package streaming

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"time"
	"vvideo/internal/jobs"
	"vvideo/internal/library"
	"vvideo/internal/mediafs"
)

type Handler struct {
	Index    *library.Index
	Priority *jobs.Priority
	slots    chan struct{}
}

func New(index *library.Index, p *jobs.Priority) *Handler {
	return &Handler{Index: index, Priority: p, slots: make(chan struct{}, 8)}
}
func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	select {
	case h.slots <- struct{}{}:
		defer func() { <-h.slots }()
	default:
		w.Header().Set("Retry-After", "1")
		http.Error(w, "media_busy", http.StatusServiceUnavailable)
		return
	}
	end := h.Priority.Begin()
	defer end()
	e, err := h.Index.Media(r.Context(), r.PathValue("id"))
	if err != nil {
		http.Error(w, "media_unavailable", 404)
		return
	}
	requested := r.URL.Query().Get("version")
	if requested == "" {
		http.Error(w, "version_required", 400)
		return
	}
	if requested != e.Version {
		http.Error(w, "media_version_changed", 409)
		return
	}
	f, _, err := h.Index.OpenMedia(r.Context(), e)
	if err != nil {
		code := 404
		if errors.Is(err, library.ErrConflict) {
			code = 409
		}
		http.Error(w, "media_unavailable_or_changed", code)
		return
	}
	defer f.Close()
	w.Header().Set("ETag", `"`+e.Version+`"`)
	w.Header().Set("Cache-Control", "private, max-age=31536000, immutable")
	contentType := mime.TypeByExtension(filepath.Ext(e.Name))
	if contentType != "" {
		w.Header().Set("Content-Type", contentType)
	}
	reader := &versionReader{f: f, fingerprint: e.Fingerprint, ctx: r.Context()}
	http.ServeContent(&progressWriter{ResponseWriter: w, controller: http.NewResponseController(w)}, r, e.Name, time.Unix(0, e.Mtime), reader)
	if reader.changed {
		panic(http.ErrAbortHandler)
	}
}

type versionReader struct {
	f           *os.File
	fingerprint string
	ctx         context.Context
	changed     bool
}

func (v *versionReader) Seek(offset int64, whence int) (int64, error) {
	return v.f.Seek(offset, whence)
}
func (v *versionReader) Read(b []byte) (int, error) {
	if err := v.ctx.Err(); err != nil {
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
	after, e := v.f.Stat()
	if e != nil || mediafs.Fingerprint(after) != v.fingerprint {
		v.changed = true
		return 0, library.ErrConflict
	}
	return n, err
}

type progressWriter struct {
	http.ResponseWriter
	controller *http.ResponseController
}

func (w *progressWriter) Write(b []byte) (int, error) {
	w.controller.SetWriteDeadline(time.Now().Add(60 * time.Second))
	return w.ResponseWriter.Write(b)
}
func (w *progressWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

type Range struct {
	Offset int64 `json:"offset"`
	Length int64 `json:"length"`
}
type Hints struct {
	Version string  `json:"version"`
	Size    int64   `json:"size"`
	Ranges  []Range `json:"ranges"`
}

func MP4Ranges(ctx context.Context, r io.ReaderAt, size int64) ([]Range, error) {
	out := []Range{}
	deadline := time.Now().Add(5 * time.Second)
	for offset, boxes := int64(0), 0; offset < size && boxes < 10000; boxes++ {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		if time.Now().After(deadline) {
			return nil, errors.New("index timeout")
		}
		var header [16]byte
		if offset > size-8 {
			return nil, errors.New("truncated box")
		}
		if _, err := r.ReadAt(header[:8], offset); err != nil {
			return nil, err
		}
		length := int64(binary.BigEndian.Uint32(header[:4]))
		head := int64(8)
		if length == 1 {
			if offset > size-16 {
				return nil, errors.New("truncated large box")
			}
			if _, err := r.ReadAt(header[8:], offset+8); err != nil {
				return nil, err
			}
			n := binary.BigEndian.Uint64(header[8:])
			if n > uint64(size-offset) {
				return nil, errors.New("invalid box size")
			}
			length = int64(n)
			head = 16
		}
		if length == 0 {
			length = size - offset
		}
		if length < head || length > size-offset {
			return nil, errors.New("invalid box length")
		}
		kind := string(header[4:8])
		switch kind {
		case "ftyp":
			if length <= 1<<20 {
				out = append(out, Range{Offset: offset, Length: length})
			}
		case "moov":
			if length > 32<<20 {
				return nil, errors.New("index exceeds 32 MB")
			}
			out = append(out, Range{Offset: offset, Length: length})
			return out, nil
		}
		offset += length
	}
	return nil, errors.New("ordinary MP4 index not found")
}
