// Package streaming serves original media bytes over HTTP Range requests and
// locates the MP4 index boxes a client may prefetch.
package streaming

import (
	"context"
	"encoding/binary"
	"encoding/json/v2"
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

const (
	// maxStreams bounds concurrent original-byte responses, including prefetch.
	maxStreams = 8
	// writeIdleTimeout is extended after every write, so a long playback is
	// never cut off while a stalled client still releases its slot.
	writeIdleTimeout = 60 * time.Second

	// MP4 index search limits: a file whose moov box lies beyond these simply
	// gets no hints and plays through ordinary Range requests.
	maxIndexBoxes   = 10000
	indexSearchTime = 5 * time.Second
	maxFtypSize     = 1 << 20
	maxMoovSize     = 32 << 20
)

// Handler serves original media bytes with Range support. It rejects bytes
// whose file version no longer matches the published index.
type Handler struct {
	Index    *library.Index
	Priority *jobs.Priority
	slots    chan struct{}
}

// New returns a Handler that reports each transfer to p.
func New(index *library.Index, p *jobs.Priority) *Handler {
	return &Handler{Index: index, Priority: p, slots: make(chan struct{}, maxStreams)}
}

// ServeHTTP answers GET /v1/media/{id}/bytes?version=. A version that no longer
// matches the index gets 409; a file rewritten mid-response aborts the
// connection so the client never mixes old and new bytes.
func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	select {
	case h.slots <- struct{}{}:
		defer func() { <-h.slots }()
	default:
		w.Header().Set("Retry-After", "1")
		fail(w, http.StatusServiceUnavailable, "media_busy")
		return
	}
	end := h.Priority.Begin()
	defer end()
	e, err := h.Index.Media(r.Context(), r.PathValue("id"))
	if err != nil {
		fail(w, http.StatusNotFound, "unavailable")
		return
	}
	requested := r.URL.Query().Get("version")
	if requested == "" {
		fail(w, http.StatusBadRequest, "version_required")
		return
	}
	if requested != e.Version {
		fail(w, http.StatusConflict, "revision_changed")
		return
	}
	f, _, err := h.Index.OpenMedia(r.Context(), e)
	if err != nil {
		if errors.Is(err, library.ErrConflict) {
			fail(w, http.StatusConflict, "revision_changed")
		} else {
			fail(w, http.StatusNotFound, "unavailable")
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
	reader := &versionReader{f: f, fingerprint: e.Fingerprint, ctx: r.Context()}
	http.ServeContent(&progressWriter{ResponseWriter: w, controller: http.NewResponseController(w)}, r, e.Name, time.Unix(0, e.Mtime), reader)
	if reader.changed {
		panic(http.ErrAbortHandler)
	}
}

// fail writes the same JSON error shape as the rest of the API.
func fail(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.MarshalWrite(w, map[string]string{"error": code})
}

// versionReader re-checks the file's fingerprint around every read.
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

// progressWriter extends the write deadline after each write.
type progressWriter struct {
	http.ResponseWriter
	controller *http.ResponseController
}

func (w *progressWriter) Write(b []byte) (int, error) {
	w.controller.SetWriteDeadline(time.Now().Add(writeIdleTimeout))
	return w.ResponseWriter.Write(b)
}

func (w *progressWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

// Range is a byte range of the original file.
type Range struct {
	Offset int64 `json:"offset"`
	Length int64 `json:"length"`
}

// Hints lists the original-offset ranges worth prefetching for one version.
type Hints struct {
	Version string  `json:"version"`
	Size    int64   `json:"size"`
	Ranges  []Range `json:"ranges"`
}

// MP4Ranges walks the top-level boxes of an MP4/MOV file and returns the ftyp
// and moov ranges at their original offsets, so a client cache keyed by those
// offsets stays valid for the real file. It fails when no moov box is found
// within the search limits.
func MP4Ranges(ctx context.Context, r io.ReaderAt, size int64) ([]Range, error) {
	out := []Range{}
	deadline := time.Now().Add(indexSearchTime)
	for offset, boxes := int64(0), 0; offset < size && boxes < maxIndexBoxes; boxes++ {
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
			if length <= maxFtypSize {
				out = append(out, Range{Offset: offset, Length: length})
			}
		case "moov":
			if length > maxMoovSize {
				return nil, errors.New("index exceeds 32 MB")
			}
			out = append(out, Range{Offset: offset, Length: length})
			return out, nil
		}
		offset += length
	}
	return nil, errors.New("ordinary MP4 index not found")
}
