package probe

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"vvideo/internal/config"
	"vvideo/internal/jobs"
	"vvideo/internal/library"
	"vvideo/internal/scanner"
	"vvideo/internal/storage"
)

func probeFixture(t *testing.T) (*library.Index, *scanner.Scanner, string) {
	t.Helper()
	base := t.TempDir()
	root := filepath.Join(base, "media")
	if err := os.Mkdir(root, 0o700); err != nil {
		t.Fatal(err)
	}
	env := map[string]string{"VVIDEO_AUTH__TOKEN": "fixture", "VVIDEO_MEDIA": fmt.Sprintf(`[{"path":%q}]`, root)}
	cfg, err := config.Load(config.Source{WorkDir: base, LookupEnv: func(key string) (string, bool) { v, ok := env[key]; return v, ok }})
	if err != nil {
		t.Fatal(err)
	}
	store, err := storage.Open(t.Context(), cfg.Database())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := store.Close(); err != nil {
			t.Error(err)
		}
	})
	index := &library.Index{Store: store}
	if err := index.Register(t.Context(), cfg); err != nil {
		t.Fatal(err)
	}
	return index, &scanner.Scanner{Config: cfg, Index: index}, root
}

func TestLateProbeCannotPublishIntoReplacement(t *testing.T) {
	index, scan, root := probeFixture(t)
	path := filepath.Join(root, "original.mp4")
	if err := os.WriteFile(path, []byte("original"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := scan.Run(t.Context(), false); err != nil {
		t.Fatal(err)
	}
	queue := jobs.NewQueue(index, nil, func() time.Time { return time.Now().Add(2 * time.Minute) })
	job, err := queue.Claim(t.Context(), "probe")
	if err != nil {
		t.Fatal(err)
	}
	worker := Worker{Queue: queue, Parameters: Parameters(10), tool: func(ctx context.Context, _ *os.File, _ library.Entry) (Result, error) {
		if err := os.WriteFile(path, []byte("replacement file content"), 0o600); err != nil {
			return Result{}, err
		}
		if err := scan.Run(ctx, false); err != nil {
			return Result{}, err
		}
		return Result{Width: 9999, Height: 9999, DurationMS: 1, EffectiveTime: 1}, nil
	}}
	if err := worker.Run(t.Context(), job); !errors.Is(err, library.ErrConflict) {
		t.Fatalf("got %v, want version conflict", err)
	}
	replacement, err := index.Media(t.Context(), job.MediaID)
	if err != nil {
		t.Fatal(err)
	}
	if replacement.Version == job.Version || replacement.Width == 9999 {
		t.Fatal("late metadata leaked into replacement")
	}
}

func TestManyProbesChangeDirectoryRevisionOnce(t *testing.T) {
	i, s, root := probeFixture(t)
	for n := range 20 {
		os.WriteFile(filepath.Join(root, fmt.Sprintf("v%02d.mp4", n)), []byte{byte(n)}, 0o600)
	}
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	later := time.Now().Add(2 * time.Minute)
	q := jobs.NewQueue(i, nil, func() time.Time { return later })
	worker := Worker{Queue: q, Parameters: Parameters(10), tool: func(_ context.Context, _ *os.File, e library.Entry) (Result, error) {
		return Result{Width: 2, Height: 2, DurationMS: 1000, EffectiveTime: e.Mtime - int64(time.Hour), CapturedSource: "fixture"}, nil
	}}
	before, e := i.Directory(t.Context(), 1)
	if e != nil {
		t.Fatal(e)
	}
	for range 20 {
		j, e := q.Claim(t.Context(), "probe")
		if e != nil {
			t.Fatal(e)
		}
		if e = worker.Run(t.Context(), j); e != nil {
			t.Fatal(e)
		}
	}
	unpublished, e := i.Directory(t.Context(), 1)
	if e != nil || unpublished.Revision != before.Revision {
		t.Fatal("probe results changed the revision before publication", e)
	}
	if moved, e := i.PublishProbedTimes(t.Context()); e != nil || moved != 20 {
		t.Fatal(moved, e)
	}
	after, e := i.Directory(t.Context(), 1)
	if e != nil || after.Revision != before.Revision+1 {
		t.Fatalf("revision moved %d times for one batch", after.Revision-before.Revision)
	}
}
