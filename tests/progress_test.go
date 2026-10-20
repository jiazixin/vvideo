package tests

import (
	"bytes"
	"database/sql"
	"encoding/json/v2"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"
	"vvideo/internal/jobs"
	"vvideo/internal/thumbnail"
)

func TestThumbnailProgressLogsActiveWindowsOnly(t *testing.T) {
	i, s, root := setup(t)
	if e := os.WriteFile(filepath.Join(root, "a.mp4"), []byte("a"), 0600); e != nil {
		t.Fatal(e)
	}
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	item := first(t, i)
	// Stale jobs belong to superseded parameters and are not part of the totals.
	states := []string{"done", "done", "done", "failed", "queued", "queued", "running", "stale"}
	if e := i.Store.Write(t.Context(), int64(len(states)), func(tx *sql.Tx) error {
		for n, state := range states {
			if _, e := tx.ExecContext(t.Context(), "INSERT INTO jobs(media_id,version,kind,parameters,due,state) VALUES(?,?,'thumbnail',?,0,?)", item.ID, item.Version, fmt.Sprint("p", n), state); e != nil {
				return e
			}
		}
		return nil
	}); e != nil {
		t.Fatal(e)
	}
	var probing float64
	if e := i.Store.Reader.QueryRow("SELECT count(*) FROM jobs WHERE kind='probe' AND state IN ('queued','running')").Scan(&probing); e != nil || probing == 0 {
		t.Fatalf("scan queued %v probes: %v", probing, e)
	}
	engine := &thumbnail.Engine{Queue: &jobs.Queue{Store: i.Store, Index: i, Now: time.Now}}
	var out bytes.Buffer
	p := &thumbnail.Progress{Engine: engine, Logger: slog.New(slog.NewJSONHandler(&out, nil))}
	start := time.Unix(1_000_000, 0)
	p.Reset(start)
	if e := p.Report(t.Context(), start.Add(5*time.Minute)); e != nil || out.Len() != 0 {
		t.Fatalf("idle window logged %q: %v", out.String(), e)
	}
	engine.Metrics.Generated.Add(3)
	engine.Metrics.GenerateNS.Add(int64(6 * time.Second))
	engine.Metrics.Failed.Add(1)
	if e := p.Report(t.Context(), start.Add(10*time.Minute)); e != nil {
		t.Fatal(e)
	}
	var got map[string]any
	if e := json.Unmarshal(out.Bytes(), &got); e != nil {
		t.Fatalf("%v: %s", e, out.String())
	}
	want := map[string]any{
		"level": "INFO", "msg": "thumbnail_progress", "window": "5m0s",
		"generated": 3.0, "failed_attempts": 1.0, "total": 7.0, "done": 3.0, "failed": 1.0, "pending": 3.0,
		"percent": 57.1, "waiting_probe": probing, "avg_ms": 2000.0, "per_minute": 0.6, "eta": "5m0s",
	}
	for k, v := range want {
		if got[k] != v {
			t.Errorf("%s = %v, want %v", k, got[k], v)
		}
	}
	out.Reset()
	if e := p.Report(t.Context(), start.Add(15*time.Minute)); e != nil || out.Len() != 0 {
		t.Fatalf("window without new work logged %q: %v", out.String(), e)
	}
}
