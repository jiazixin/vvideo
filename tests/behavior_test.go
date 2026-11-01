package tests

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/binary"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"testing"
	"time"
	"uuid"

	"vvideo/internal/app"
	"vvideo/internal/config"
	"vvideo/internal/jobs"
	"vvideo/internal/library"
	"vvideo/internal/scanner"
	"vvideo/internal/storage"
	"vvideo/internal/streaming"
	"vvideo/internal/thumbnail"
)

func setup(t *testing.T) (*library.Index, *scanner.Scanner, string) {
	t.Helper()
	base, e := config.Canonical(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	root := filepath.Join(base, "media")
	if e = os.Mkdir(root, 0o700); e != nil {
		t.Fatal(e)
	}
	env := map[string]string{"VVIDEO_AUTH__TOKEN": "fixture", "VVIDEO_MEDIA": fmt.Sprintf(`[{"path":%q}]`, root)}
	lookupEnv := func(k string) (string, bool) {
		v, ok := env[k]
		return v, ok
	}
	c, e := config.Load(config.Source{WorkDir: base, LookupEnv: lookupEnv})
	if e != nil {
		t.Fatal(e)
	}
	db, e := storage.Open(t.Context(), c.Database())
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { db.Close() })
	i := &library.Index{Store: db}
	if e = i.Register(t.Context(), c); e != nil {
		t.Fatal(e)
	}
	return i, &scanner.Scanner{Config: c, Index: i}, root
}
func first(t *testing.T, i *library.Index) library.Entry {
	t.Helper()
	ls, e := i.Libraries(t.Context())
	if e != nil {
		t.Fatal(e)
	}
	p, e := i.List(t.Context(), ls[0].DirectoryID, library.ListOptions{})
	if e != nil || len(p.Items) == 0 {
		t.Fatalf("page: %v %+v", e, p)
	}
	return p.Items[0]
}
func TestRangeAndVersionIsolation(t *testing.T) {
	i, s, root := setup(t)
	data := []byte("0123456789abcdefghijklmnopqrstuvwxyz")
	path := filepath.Join(root, "sample.mp4")
	if e := os.WriteFile(path, data, 0o600); e != nil {
		t.Fatal(e)
	}
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	entry := first(t, i)
	mux := http.NewServeMux()
	mux.Handle("GET /media/{id}", app.NewStreamHandler(i, jobs.NewPriority(time.Now)))
	url := "/media/" + entry.ID + "?version=" + entry.Version
	for _, tc := range []struct {
		rangeHeader string
		code        int
		want        string
	}{{"bytes=3-8", 206, "345678"}, {"bytes=-4", 206, "wxyz"}, {"bytes=36-", 416, ""}, {"", 200, string(data)}} {
		t.Run(tc.rangeHeader, func(t *testing.T) {
			r := httptest.NewRequest("GET", url, nil)
			r.Header.Set("Range", tc.rangeHeader)
			w := httptest.NewRecorder()
			mux.ServeHTTP(w, r)
			if w.Code != tc.code {
				t.Fatalf("status %d", w.Code)
			}
			if tc.want != "" && w.Body.String() != tc.want {
				t.Fatalf("wrong bytes %q", w.Body.String())
			}
			if tc.code != 416 && w.Header().Get("ETag") != `"`+entry.Version+`"` {
				t.Fatal("wrong version tag")
			}
		})
	}
	r := httptest.NewRequest("GET", url, nil)
	r.Header.Set("If-None-Match", `"`+entry.Version+`"`)
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, r)
	if w.Code != 304 {
		t.Fatal(w.Code)
	}
	if e := os.WriteFile(path, []byte("changed bytes with a different length"), 0o600); e != nil {
		t.Fatal(e)
	}
	w = httptest.NewRecorder()
	mux.ServeHTTP(w, httptest.NewRequest("GET", url, nil))
	if w.Code != 409 {
		t.Fatalf("old version exposed new bytes: %d", w.Code)
	}
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	replacement := first(t, i)
	if replacement.ID != entry.ID || replacement.Version == entry.Version {
		t.Fatal("unstable ID or unchanged version")
	}
	w = httptest.NewRecorder()
	mux.ServeHTTP(w, httptest.NewRequest("GET", url, nil))
	if w.Code != 409 {
		t.Fatal("old cached URL accepted")
	}
}
func TestMP4OriginalOffsetsAndMalformedFallback(t *testing.T) {
	box := func(kind string, n int) []byte {
		b := make([]byte, n)
		binary.BigEndian.PutUint32(b, uint32(n))
		copy(b[4:8], kind)
		return b
	}
	data := bytes.Join([][]byte{box("ftyp", 16), box("mdat", 4096), box("moov", 64)}, nil)
	ranges, e := streaming.MP4Ranges(t.Context(), bytes.NewReader(data), int64(len(data)))
	if e != nil {
		t.Fatal(e)
	}
	if len(ranges) != 2 || ranges[1].Offset != 4112 || ranges[1].Length != 64 {
		t.Fatalf("offsets %+v", ranges)
	}
	for _, b := range [][]byte{nil, []byte("invalid"), {0, 0, 0, 1, 'm', 'o', 'o', 'v', 255, 255, 255, 255, 255, 255, 255, 255}, box("mdat", 8)} {
		if _, e := streaming.MP4Ranges(t.Context(), bytes.NewReader(b), int64(len(b))); e == nil {
			t.Fatalf("malformed index accepted: %v", b)
		}
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if _, e := streaming.MP4Ranges(ctx, bytes.NewReader(data), int64(len(data))); !errors.Is(e, context.Canceled) {
		t.Fatal(e)
	}
}
func TestExactGroupsKeysetsAndDeepRestore(t *testing.T) {
	i, _, _ := setup(t)
	ls, e := i.Libraries(t.Context())
	if e != nil {
		t.Fatal(e)
	}
	d, e := i.Directory(t.Context(), ls[0].DirectoryID)
	if e != nil {
		t.Fatal(e)
	}
	n := 537
	if x := os.Getenv("VVIDEO_SCALE_ITEMS"); x != "" {
		n, e = strconv.Atoi(x)
		if e != nil || n < 1 || n > 510000 {
			t.Fatal("invalid scale")
		}
	}
	start := time.Now()
	gen, e := i.BeginBuild(t.Context(), d)
	if e != nil {
		t.Fatal(e)
	}
	var deep string
	for offset := 0; offset < n; offset += 128 {
		batch := make([]library.Entry, 0, 128)
		for j := offset; j < min(n, offset+128); j++ {
			id := uuid.New().String()
			if j == n-27 {
				deep = id
			}
			batch = append(batch, library.Entry{ID: id, Version: uuid.New().String(), Name: fmt.Sprintf("%08d.mp4", j), Kind: "video", Fingerprint: fmt.Sprint(j), Size: 100, Mtime: int64(j / 3), EffectiveTime: int64(j / 3), NewVersion: true, Present: true, State: "visible"})
		}
		if e = i.AddBatch(t.Context(), library.GenerationBatch{Directory: d, Generation: gen, Entries: batch, ObservedAt: time.Now()}); e != nil {
			t.Fatal(e)
		}
	}
	if e = i.Publish(t.Context(), library.Publication{Directory: d, Generation: gen, Identity: "fixture", Round: 1, Validate: func() error { return nil }}); e != nil {
		t.Fatal(e)
	}
	d, e = i.Directory(t.Context(), d.ID)
	if e != nil {
		t.Fatal(e)
	}
	if e = i.RebuildGroups(t.Context(), d.ID, jobs.NewPriority(time.Now).Wait); e != nil {
		t.Fatal(e)
	}
	t.Logf("seed+groups items=%d elapsed=%s", n, time.Since(start))
	for _, field := range []string{"capture", "mtime", "name"} {
		for _, direction := range []string{"asc", "desc"} {
			t.Run(field+direction, func(t *testing.T) {
				opts := library.ListOptions{Sort: field, Direction: direction, Limit: 100}
				total := 0
				groupFirst, expected := "", ""
				var previous library.Entry
				for {
					p, e := i.List(t.Context(), d.ID, opts)
					if e != nil {
						t.Fatal(e)
					}
					if len(p.Items) == 0 {
						break
					}
					for _, item := range p.Items {
						if total%10 == 0 {
							groupFirst = item.ID
						}
						if item.ID == deep {
							expected = groupFirst
						}
						if total > 0 {
							a, b := previous.EffectiveTime, item.EffectiveTime
							if field == "mtime" {
								a, b = previous.Mtime, item.Mtime
							}
							less := a < b || a == b && previous.ID < item.ID
							if field == "name" {
								less = previous.Name < item.Name || previous.Name == item.Name && previous.ID < item.ID
							}
							if (direction == "asc") != less {
								t.Fatal("unstable or repeated keyset ordering")
							}
						}
						previous = item
						total++
					}
					opts.Cursor = p.Next
					if total > n {
						t.Fatal("cursor cycle")
					}
					if opts.Cursor == "" {
						break
					}
				}
				if total != n || expected == "" {
					t.Fatal("lost items or anchor")
				}
				opts.Cursor = ""
				opts.Anchor = deep
				began := time.Now()
				p, e := i.List(t.Context(), d.ID, opts)
				if e != nil {
					t.Fatal(e)
				}
				if len(p.Items) == 0 || p.Items[0].ID != expected {
					t.Fatal("restoration starts inside group")
				}
				t.Logf("deep restore %s", time.Since(began))
				for round := range 3 {
					for _, anchor := range []string{"", deep} {
						latencies := make([]time.Duration, 20)
						measured := opts
						measured.Anchor = anchor
						for sample := range latencies {
							start := time.Now()
							if _, err := i.List(t.Context(), d.ID, measured); err != nil {
								t.Fatal(err)
							}
							latencies[sample] = time.Since(start)
						}
						slices.Sort(latencies)
						t.Logf("query round=%d deep=%t samples=20 p50=%s p95=%s max=%s", round+1, anchor != "", latencies[9], latencies[18], latencies[19])
					}
				}
				opts.Anchor = ""
				opts.Cursor = p.Next
				opts.Previous = true
				prior, e := i.List(t.Context(), d.ID, opts)
				if e != nil {
					t.Fatal(e)
				}
				if len(prior.Items) > 100 {
					t.Fatal("unbounded page")
				}
			})
		}
	}
	// The restore query uses the anchors index, not a prefix COUNT / OFFSET scan.
	var token string
	if e := i.Store.QueryRow(t.Context(), "SELECT group_token FROM directories WHERE id=?", d.ID).Scan(&token); e != nil {
		t.Fatal(e)
	}
	rows, e := i.Store.Query(t.Context(), "EXPLAIN QUERY PLAN SELECT media_id FROM group_anchors WHERE directory_id=? AND token=? AND sort='name' AND direction='asc' AND (name_key,media_id)<=(?,?) ORDER BY name_key DESC,media_id DESC LIMIT 1", d.ID, token, "x", "y")
	if e != nil {
		t.Fatal(e)
	}
	defer rows.Close()
	for rows.Next() {
		var a, b, c int
		var plan string
		if e = rows.Scan(&a, &b, &c, &plan); e != nil {
			t.Fatal(e)
		}
		t.Log(plan)
	}
}
func TestThumbnailPinsLedgerRecoveryAndNoAutomaticRefill(t *testing.T) {
	i, s, root := setup(t)
	for _, name := range []string{"a.mp4", "b.mp4", "c.mp4"} {
		os.WriteFile(filepath.Join(root, name), []byte("fixture"), 0o600)
	}
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	libs, _ := i.Libraries(t.Context())
	p, e := i.List(t.Context(), libs[0].DirectoryID, library.ListOptions{Sort: "name", Direction: "asc"})
	if e != nil {
		t.Fatal(e)
	}
	q := jobs.NewQueue(i, nil, time.Now)
	c := s.Config
	c.Cache.MaxSize = 15
	engine := thumbnail.New(c, q)
	os.MkdirAll(c.Cache.Path, 0o700)
	for j, item := range p.Items {
		key := engine.Key(item)
		os.WriteFile(filepath.Join(c.Cache.Path, key+".webp"), []byte("0123456789"), 0o600)
		if e = i.Store.Write(t.Context(), 0, func(tx *sql.Tx) error {
			if _, e := tx.ExecContext(t.Context(), "INSERT INTO thumbnails VALUES(?,?,?,?,?,'ready')", key, item.ID, item.Version, 10, j); e != nil {
				return e
			}
			if _, e := tx.ExecContext(t.Context(), "UPDATE counters SET value=value+10 WHERE name='thumbnail_bytes'"); e != nil {
				return e
			}
			if _, e := tx.ExecContext(t.Context(), "UPDATE versions SET status='ready' WHERE media_id=? AND version=?", item.ID, item.Version); e != nil {
				return e
			}
			_, e := tx.ExecContext(t.Context(), "INSERT INTO jobs(media_id,version,kind,parameters,due,state,attempts)VALUES(?,?,'thumbnail',?,0,'done',3)", item.ID, item.Version, engine.Parameters)
			return e
		}); e != nil {
			t.Fatal(e)
		}
	}
	f, release, e := engine.Open(p.Items[0])
	if e != nil {
		t.Fatal(e)
	}
	if e = engine.Evict(t.Context(), 0); e != nil {
		t.Fatal(e)
	}
	n, e := engine.Bytes(t.Context())
	if e != nil || n != 10 {
		t.Fatal("wrong ledger", e, n)
	}
	engine.Config.Cache.MaxSize = 5
	if e = engine.Evict(t.Context(), 0); e == nil {
		t.Fatal("evicted active cover")
	}
	f.Close()
	release()
	if e = engine.Evict(t.Context(), 0); e != nil {
		t.Fatal(e)
	}
	if e = s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	var pending int
	if e = i.Store.QueryRow(t.Context(), "SELECT count(*) FROM jobs WHERE kind='thumbnail' AND state='queued'").Scan(&pending); e != nil || pending != 0 {
		t.Fatal("unchanged scan refills evicted covers", e, pending)
	}
	if e = q.EnqueueThumbnail(t.Context(), p.Items[0], engine.Parameters, true); e != nil {
		t.Fatal(e)
	}
	task, e := q.Claim(t.Context(), "thumbnail")
	if e != nil || task.Attempts != 1 {
		t.Fatal("on-demand regeneration retains old retry count", e, task.Attempts)
	}
	q.Finish(t.Context(), task, "done", "")
	orphan := filepath.Join(c.Cache.Path, "0123456789012345678901234567890123456789012345678901234567890123.webp")
	os.WriteFile(orphan, []byte("orphan"), 0o600)
	if e = engine.Reconcile(t.Context()); e != nil {
		t.Fatal(e)
	}
	if _, e = os.Stat(orphan); !errors.Is(e, os.ErrNotExist) {
		t.Fatal("orphan survived", e)
	}
	changed := c
	changed.Cache.Percent = 20
	if thumbnail.New(changed, q).Key(p.Items[0]) == engine.Key(p.Items[0]) {
		t.Fatal("cover parameters missing from key")
	}
}

// TestForegroundCoverClaimsAheadOfBacklog: an on-demand cover enqueued after a
// large, earlier-due background backlog is claimed first, and a finished
// foreground job re-queued in the background loses its precedence.
func TestForegroundCoverClaimsAheadOfBacklog(t *testing.T) {
	i, s, root := setup(t)
	for _, name := range []string{"a.mp4", "b.mp4"} {
		os.WriteFile(filepath.Join(root, name), []byte(name), 0o600)
	}
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	libs, _ := i.Libraries(t.Context())
	p, e := i.List(t.Context(), libs[0].DirectoryID, library.ListOptions{Sort: "name", Direction: "asc"})
	if e != nil || len(p.Items) != 2 {
		t.Fatal(e, p)
	}
	background, wanted := p.Items[0], p.Items[1]
	const backlog = 5000
	if e = i.Store.Write(t.Context(), backlog, func(tx *sql.Tx) error {
		stmt, e := tx.PrepareContext(t.Context(), "INSERT INTO jobs(media_id,version,kind,parameters,due) VALUES(?,?,'thumbnail',?,?)")
		if e != nil {
			return e
		}
		defer stmt.Close()
		for n := range backlog {
			if _, e = stmt.ExecContext(t.Context(), background.ID, background.Version, fmt.Sprintf("bg-%05d", n), n); e != nil {
				return e
			}
		}
		return nil
	}); e != nil {
		t.Fatal(e)
	}
	q := jobs.NewQueue(i, nil, time.Now)
	if e = q.EnqueueThumbnail(t.Context(), wanted, "cover", true); e != nil {
		t.Fatal(e)
	}
	claim := func() jobs.Job {
		t.Helper()
		j, e := q.Claim(t.Context(), "thumbnail")
		if e != nil {
			t.Fatal(e)
		}
		return j
	}
	fg := claim()
	if fg.MediaID != wanted.ID || fg.Parameters != "cover" {
		t.Fatalf("claimed %s %s ahead of the on-demand cover", fg.MediaID, fg.Parameters)
	}
	if e = q.Finish(t.Context(), fg, "done", ""); e != nil {
		t.Fatal(e)
	}
	if j := claim(); j.Parameters != "bg-00000" {
		t.Fatalf("background order broken: %s", j.Parameters)
	}
	if e = q.EnqueueThumbnail(t.Context(), wanted, "cover", false); e != nil {
		t.Fatal(e)
	}
	if j := claim(); j.Parameters != "bg-00001" {
		t.Fatalf("re-queued background job kept foreground precedence: %s", j.Parameters)
	}
}

// TestPlaybackCooldown covers the gate list reordering still waits on; scans,
// probes and covers no longer consult it.
func TestShutdownInterruptedJobKeepsItsAttempts(t *testing.T) {
	i, s, root := setup(t)
	os.WriteFile(filepath.Join(root, "a.mp4"), []byte("first"), 0o600)
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	later := time.Now().Add(2 * time.Minute)
	q := jobs.NewQueue(i, nil, func() time.Time { return later })
	for round := range 5 {
		ctx, cancel := context.WithCancel(t.Context())
		ran := false
		q.Worker(ctx, "probe", func(ctx context.Context, j jobs.Job) error {
			ran = true
			cancel() // shutdown arrives while the tool runs
			return ctx.Err()
		})
		cancel()
		if !ran {
			t.Fatalf("round %d: job was not claimed", round)
		}
	}
	var state string
	var attempts int
	if e := i.Store.QueryRow(t.Context(), "SELECT state,attempts FROM jobs WHERE kind='probe'").Scan(&state, &attempts); e != nil {
		t.Fatal(e)
	}
	if state != "queued" || attempts != 0 {
		t.Fatalf("interrupted job became %s with %d attempts", state, attempts)
	}
}
