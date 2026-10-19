package tests

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
	"vvideo/internal/jobs"
	"vvideo/internal/streaming"
)

type stalledResponse struct {
	*httptest.ResponseRecorder
	ctx     context.Context
	entered chan struct{}
	once    sync.Once
}

func (w *stalledResponse) Write(p []byte) (int, error) {
	w.once.Do(func() { close(w.entered) })
	<-w.ctx.Done()
	return 0, w.ctx.Err()
}

func TestStreamAdmissionAndCancellationRelease(t *testing.T) {
	i, scan, root := setup(t)
	if err := os.WriteFile(filepath.Join(root, "sample.mp4"), bytes.Repeat([]byte("original"), 10000), 0600); err != nil {
		t.Fatal(err)
	}
	if err := scan.Run(t.Context(), false); err != nil {
		t.Fatal(err)
	}
	e := first(t, i)
	mux := http.NewServeMux()
	mux.Handle("GET /media/{id}", streaming.New(i, jobs.NewPriority()))
	url := "/media/" + e.ID + "?version=" + e.Version
	ctx, cancel := context.WithCancel(t.Context())
	var wg sync.WaitGroup
	defer func() { cancel(); wg.Wait() }()
	for range 8 {
		w := &stalledResponse{ResponseRecorder: httptest.NewRecorder(), ctx: ctx, entered: make(chan struct{})}
		wg.Go(func() { mux.ServeHTTP(w, httptest.NewRequestWithContext(ctx, "GET", url, nil)) })
		select {
		case <-w.entered:
		case <-t.Context().Done():
			t.Fatal("stream did not reach output")
		}
	}
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, httptest.NewRequest("GET", url, nil))
	if w.Code != 503 || w.Header().Get("Retry-After") != "1" {
		t.Fatal("unbounded stream admission", w.Code)
	}
	cancel()
	wg.Wait()
	w = httptest.NewRecorder()
	mux.ServeHTTP(w, httptest.NewRequest("GET", url, nil))
	if w.Code != 200 || w.Body.Len() != 80000 {
		t.Fatal("cancelled streams retained their slots", w.Code, w.Body.Len())
	}
}

type replacementResponse struct {
	*httptest.ResponseRecorder
	replace func()
}

func (w *replacementResponse) Write(p []byte) (int, error) {
	n, err := w.ResponseRecorder.Write(p)
	if w.replace != nil {
		w.replace()
		w.replace = nil
	}
	return n, err
}

func TestInFlightReplacementAbortsWithoutNewBytes(t *testing.T) {
	i, scan, root := setup(t)
	path := filepath.Join(root, "sample.mp4")
	original := bytes.Repeat([]byte("A"), 128<<10)
	if err := os.WriteFile(path, original, 0600); err != nil {
		t.Fatal(err)
	}
	if err := scan.Run(t.Context(), false); err != nil {
		t.Fatal(err)
	}
	e := first(t, i)
	mux := http.NewServeMux()
	mux.Handle("GET /media/{id}", streaming.New(i, jobs.NewPriority()))
	w := &replacementResponse{ResponseRecorder: httptest.NewRecorder(), replace: func() {
		if err := os.WriteFile(path, bytes.Repeat([]byte("B"), 256<<10), 0600); err != nil {
			t.Fatal(err)
		}
	}}
	aborted := false
	func() {
		defer func() {
			if p := recover(); p != nil {
				err, ok := p.(error)
				if !ok || !errors.Is(err, http.ErrAbortHandler) {
					t.Fatalf("unexpected panic: %v", p)
				}
				aborted = true
			}
		}()
		mux.ServeHTTP(w, httptest.NewRequest("GET", "/media/"+e.ID+"?version="+e.Version, nil))
	}()
	if !aborted || w.Body.Len() == 0 || w.Body.Len() >= len(original) || !bytes.Equal(w.Body.Bytes(), original[:w.Body.Len()]) {
		t.Fatal("in-flight replacement mixed versions or completed stale body", aborted, w.Body.Len())
	}
}

func TestPreservedMtimeOverwriteCreatesNewVersion(t *testing.T) {
	i, scan, root := setup(t)
	path := filepath.Join(root, "sample.mp4")
	original := bytes.Repeat([]byte("A"), 128<<10)
	if err := os.WriteFile(path, original, 0600); err != nil {
		t.Fatal(err)
	}
	if err := scan.Run(t.Context(), false); err != nil {
		t.Fatal(err)
	}
	entry := first(t, i)
	before, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	replacement := bytes.Repeat([]byte("B"), len(original))
	if err := os.WriteFile(path, replacement, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(path, before.ModTime(), before.ModTime()); err != nil {
		t.Fatal(err)
	}
	after, err := os.Stat(path)
	if err != nil || !os.SameFile(before, after) || before.Size() != after.Size() || !before.ModTime().Equal(after.ModTime()) {
		t.Fatal("fixture did not preserve inode/size/mtime", err)
	}
	mux := http.NewServeMux()
	mux.Handle("GET /media/{id}", streaming.New(i, jobs.NewPriority()))
	request := func(version string) *httptest.ResponseRecorder {
		t.Helper()
		req := httptest.NewRequestWithContext(t.Context(), "GET", "/media/"+entry.ID+"?version="+version, nil)
		req.Header.Set("Range", "bytes=0-15")
		response := httptest.NewRecorder()
		mux.ServeHTTP(response, req)
		return response
	}
	if response := request(entry.Version); response.Code != 409 {
		t.Fatalf("preserved-mtime overwrite exposed bytes under old version: status=%d body=%q", response.Code, response.Body.String())
	}
	if err := scan.Run(t.Context(), false); err != nil {
		t.Fatal(err)
	}
	current := first(t, i)
	if current.ID != entry.ID || current.Version == entry.Version {
		t.Fatal("preserved-mtime update did not retain ID/change version")
	}
	if response := request(entry.Version); response.Code != 409 {
		t.Fatal("old cache version accepted after rescan", response.Code)
	}
	if response := request(current.Version); response.Code != 206 || !bytes.Equal(response.Body.Bytes(), replacement[:16]) || response.Header().Get("ETag") != `"`+current.Version+`"` {
		t.Fatal("new range/ETag did not match replacement", response.Code)
	}
	if err := scan.Run(t.Context(), false); err != nil {
		t.Fatal(err)
	}
	if unchanged := first(t, i); unchanged.Version != current.Version {
		t.Fatal("reading/unchanged rescan created a spurious version")
	}
}

func TestInFlightPreservedMtimeOverwriteAborts(t *testing.T) {
	i, scan, root := setup(t)
	path := filepath.Join(root, "sample.mp4")
	original := bytes.Repeat([]byte("A"), 128<<10)
	if err := os.WriteFile(path, original, 0600); err != nil {
		t.Fatal(err)
	}
	if err := scan.Run(t.Context(), false); err != nil {
		t.Fatal(err)
	}
	entry := first(t, i)
	before, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	mux.Handle("GET /media/{id}", streaming.New(i, jobs.NewPriority()))
	response := &replacementResponse{ResponseRecorder: httptest.NewRecorder(), replace: func() {
		if err := os.WriteFile(path, bytes.Repeat([]byte("B"), len(original)), 0600); err != nil {
			t.Fatal(err)
		}
		if err := os.Chtimes(path, before.ModTime(), before.ModTime()); err != nil {
			t.Fatal(err)
		}
	}}
	aborted := false
	func() {
		defer func() {
			if p := recover(); p != nil {
				err, ok := p.(error)
				if !ok || !errors.Is(err, http.ErrAbortHandler) {
					t.Fatalf("unexpected panic: %v", p)
				}
				aborted = true
			}
		}()
		mux.ServeHTTP(response, httptest.NewRequestWithContext(t.Context(), "GET", "/media/"+entry.ID+"?version="+entry.Version, nil))
	}()
	if !aborted || response.Body.Len() == 0 || response.Body.Len() >= len(original) || !bytes.Equal(response.Body.Bytes(), original[:response.Body.Len()]) {
		t.Fatal("preserved-mtime in-flight overwrite mixed or completed stale bytes", aborted, response.Body.Len())
	}
}

// A real server write timeout must bound API responses but not media streams,
// which extend their deadline after every write.
func TestStreamOutlivesServerWriteTimeout(t *testing.T) {
	i, scan, root := setup(t)
	data := bytes.Repeat([]byte("0123456789abcdef"), 1<<19) // 8 MiB
	if err := os.WriteFile(filepath.Join(root, "long.mp4"), data, 0600); err != nil {
		t.Fatal(err)
	}
	if err := scan.Run(t.Context(), false); err != nil {
		t.Fatal(err)
	}
	e := first(t, i)
	mux := http.NewServeMux()
	mux.Handle("GET /media/{id}", streaming.New(i, jobs.NewPriority()))
	server := httptest.NewUnstartedServer(mux)
	server.Config.WriteTimeout = 200 * time.Millisecond
	server.Start()
	defer server.Close()
	response, err := http.Get(server.URL + "/media/" + e.ID + "?version=" + e.Version)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	started := time.Now()
	var received int
	buffer := make([]byte, 256<<10)
	for {
		n, err := response.Body.Read(buffer)
		received += n
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			t.Fatalf("stream cut after %d bytes and %s: %v", received, time.Since(started), err)
		}
		time.Sleep(15 * time.Millisecond) // a slow client
	}
	if received != len(data) || time.Since(started) < 300*time.Millisecond {
		t.Fatalf("received %d of %d bytes in %s", received, len(data), time.Since(started))
	}
}
