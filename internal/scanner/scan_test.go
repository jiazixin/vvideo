package scanner

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
	"vvideo/internal/config"
	"vvideo/internal/jobs"
	"vvideo/internal/library"
	"vvideo/internal/mediafs"
	"vvideo/internal/storage"
)

func fixture(t *testing.T) (*Scanner, string) {
	t.Helper()
	base := t.TempDir()
	base, _ = config.Canonical(base)
	root := filepath.Join(base, "media")
	if e := os.Mkdir(root, 0700); e != nil {
		t.Fatal(e)
	}
	c, e := config.Load(base, []string{"VVIDEO_AUTH__TOKEN=test", fmt.Sprintf(`VVIDEO_MEDIA=[{"path":%q}]`, root)})
	if e != nil {
		t.Fatal(e)
	}
	db, e := storage.Open(t.Context(), c.Storage)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { db.Close() })
	index := &library.Index{Store: db}
	if e = index.Register(t.Context(), c); e != nil {
		t.Fatal(e)
	}
	return &Scanner{Config: c, Index: index, Priority: jobs.NewPriority(), Now: time.Now}, root
}
func count(t *testing.T, s *Scanner) int {
	t.Helper()
	var n int
	if e := s.Index.Store.Reader.QueryRow(`SELECT count(*) FROM entries e JOIN directories d ON d.id=e.directory_id WHERE e.generation=d.generation AND e.state='visible'`).Scan(&n); e != nil {
		t.Fatal(e)
	}
	return n
}
func create(t *testing.T, root, name string) {
	t.Helper()
	if e := os.WriteFile(filepath.Join(root, name), []byte("fixture"), 0600); e != nil {
		t.Fatal(e)
	}
}
func TestWalkBoundedPagesAndUnchangedScan(t *testing.T) {
	s, root := fixture(t)
	for i := range 257 {
		create(t, root, fmt.Sprintf("%04d.mp4", i))
	}
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	if n := count(t, s); n != 257 {
		t.Fatalf("got %d entries", n)
	}
	writes := s.Index.Store.Metrics.Rows.Load()
	var versions int
	s.Index.Store.Reader.QueryRow("SELECT count(*) FROM versions").Scan(&versions)
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	if s.Index.Store.Metrics.Rows.Load() != writes {
		t.Fatal("unchanged scan writes media rows")
	}
	var n int
	s.Index.Store.Reader.QueryRow("SELECT count(*) FROM versions").Scan(&n)
	if n != versions {
		t.Fatal("unchanged scan changes versions")
	}
	var tasks int
	s.Index.Store.Reader.QueryRow("SELECT count(*) FROM jobs").Scan(&tasks)
	if tasks != 257 {
		t.Fatal("duplicate or absent jobs")
	}
	if s.Metrics.Peak.Load() > 2 {
		t.Fatal("attribute concurrency exceeded")
	}
}
func TestFailedPublicationKeepsOldGeneration(t *testing.T) {
	s, root := fixture(t)
	create(t, root, "old.mp4")
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	for i := range 257 {
		create(t, root, fmt.Sprintf("new%04d.mp4", i))
	}
	n := 0
	s.Fault = func(at string) error {
		if at == "build" {
			n++
			if n == 2 {
				return errors.New("injected second batch fault")
			}
		}
		return nil
	}
	if e := s.Run(t.Context(), false); e == nil {
		t.Fatal("fault invisible")
	}
	if count(t, s) != 1 {
		t.Fatal("half generation published")
	}
	s.Fault = nil
	if e := s.Index.Recover(t.Context()); e != nil {
		t.Fatal(e)
	}
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	if count(t, s) != 258 {
		t.Fatal("recovery lost entries")
	}
}
func TestMissingNeedsConsecutiveHealthyRoots(t *testing.T) {
	s, root := fixture(t)
	create(t, root, "one.mp4")
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	os.Remove(filepath.Join(root, "one.mp4"))
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	if count(t, s) != 1 {
		t.Fatal("hidden on first absence")
	}
	s.Stat = func(*os.File, string) (fs.FileInfo, error) { return nil, errors.New("permission fault") }
	create(t, root, "trigger.mp4")
	if e := s.Run(t.Context(), false); e == nil {
		t.Fatal("fault invisible")
	}
	os.Remove(filepath.Join(root, "trigger.mp4"))
	s.Stat = mediafs.StatAt
	for i := range 2 {
		if e := s.Run(t.Context(), false); e != nil {
			t.Fatal(e)
		}
		want := 1
		if i == 1 {
			want = 0
		}
		if count(t, s) != want {
			t.Fatalf("after healthy round %d count=%d", i, count(t, s))
		}
	}
	create(t, root, "one.mp4")
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	if count(t, s) != 1 {
		t.Fatal("reappeared entry not restored")
	}
}
func TestSlowFilesystemDoesNotHoldDatabase(t *testing.T) {
	s, root := fixture(t)
	create(t, root, "a.mp4")
	entered := make(chan struct{}, 1)
	release := make(chan struct{})
	s.Stat = func(f *os.File, name string) (fs.FileInfo, error) {
		select {
		case entered <- struct{}{}:
		default:
		}
		<-release
		return mediafs.StatAt(f, name)
	}
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- s.Run(ctx, false) }()
	<-entered
	queryCtx, stop := context.WithTimeout(t.Context(), time.Second)
	defer stop()
	if _, e := s.Index.Libraries(queryCtx); e != nil {
		t.Fatal(e)
	}
	if e := s.Index.Store.Write(queryCtx, 0, func(tx *sql.Tx) error {
		_, e := tx.Exec("UPDATE counters SET value=value WHERE name='thumbnail_bytes'")
		return e
	}); e != nil {
		t.Fatal(e)
	}
	cancel()
	close(release)
	if e := <-done; !errors.Is(e, context.Canceled) {
		t.Fatal(e)
	}
}
func TestRootBoundaryAndIdentity(t *testing.T) {
	s, root := fixture(t)
	outside := t.TempDir()
	create(t, outside, "private.mp4")
	if e := os.Symlink(outside, filepath.Join(root, "link")); e != nil {
		t.Fatal(e)
	}
	if e := os.Symlink(filepath.Join(outside, "private.mp4"), filepath.Join(root, "symlink.mp4")); e != nil {
		t.Fatal(e)
	}
	create(t, root, "allowed.mp4")
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	if count(t, s) != 1 {
		t.Fatal("followed symlink")
	}
	os.Rename(root, root+".old")
	os.Mkdir(root, 0700)
	if e := s.Run(t.Context(), false); e == nil {
		t.Fatal("replacement root accepted")
	}
	if count(t, s) != 1 {
		t.Fatal("replacement root deleted old index")
	}
}
func TestMetadataGlobalParallelLimitAndCancel(t *testing.T) {
	for _, p := range []int{1, 2, 4} {
		t.Run(fmt.Sprint(p), func(t *testing.T) {
			d := t.TempDir()
			for i := range 20 {
				create(t, d, fmt.Sprint(i)+".mp4")
			}
			es, e := os.ReadDir(d)
			if e != nil {
				t.Fatal(e)
			}
			entered := make(chan struct{}, p)
			release := make(chan struct{})
			var active, peak atomic.Int64
			stat := func(name string) (Attributes, error) {
				n := active.Add(1)
				for old := peak.Load(); n > old; old = peak.Load() {
					if peak.CompareAndSwap(old, n) {
						break
					}
				}
				entered <- struct{}{}
				<-release
				active.Add(-1)
				return Attributes{Name: name}, nil
			}
			ctx, cancel := context.WithCancel(t.Context())
			done := make(chan error, 1)
			go func() { _, e := Collect(ctx, es, p, stat, &Metrics{}); done <- e }()
			for range p {
				<-entered
			}
			cancel()
			select {
			case <-done:
				t.Fatal("did not join in-flight work")
			default:
			}
			close(release)
			if e := <-done; !errors.Is(e, context.Canceled) {
				t.Fatal(e)
			}
			if peak.Load() != int64(p) || active.Load() != 0 {
				t.Fatal("wrong concurrency or leak")
			}
		})
	}
}
