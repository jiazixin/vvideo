package scanner

import (
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"
	"uuid"

	"vvideo/internal/jobs"
	"vvideo/internal/library"
	"vvideo/internal/mediafs"
	"vvideo/internal/probe"
)

func TestChangeDuringPublicationIsRescanned(t *testing.T) {
	s, root := fixture(t)
	i := s.Index
	path := filepath.Join(root, "new.mp4")
	os.WriteFile(path, []byte("new"), 0o600)
	s.onStep = func(at string) error {
		if at == "publish" {
			os.WriteFile(path, []byte("changed between observation and publication"), 0o600)
		}
		return nil
	}
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	observed := firstPublished(t, i)
	s.onStep = nil
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	if current := firstPublished(t, i); current.ID != observed.ID || current.Version == observed.Version {
		t.Fatal("rewrite during publication was not picked up by the next scan")
	}
}

func firstPublished(t *testing.T, index *library.Index) library.Entry {
	t.Helper()
	libraries, err := index.Libraries(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	page, err := index.List(t.Context(), libraries[0].DirectoryID, library.ListOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Items) == 0 {
		t.Fatal("expected a published item")
	}
	return page.Items[0]
}

func TestProbePublicationInterleavingAndLateOldResult(t *testing.T) {
	s, root := fixture(t)
	i := s.Index
	path := filepath.Join(root, "original.mp4")
	os.WriteFile(path, []byte("original"), 0o600)
	now := time.Now()
	s.now = func() time.Time { return now }
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	now = now.Add(time.Minute)
	q := jobs.NewQueue(i, nil, func() time.Time { return now })
	j, e := q.Claim(t.Context(), "probe")
	if e != nil {
		t.Fatal(e)
	}
	toolDir := t.TempDir()
	script := "#!/bin/sh\nprintf '%s\\n' '{\"streams\":[{\"codec_type\":\"video\",\"width\":1920,\"height\":1080}],\"format\":{\"duration\":\"8\",\"tags\":{\"creation_time\":\"2001-01-01T00:00:00Z\"}}}'\n"
	if err := os.WriteFile(filepath.Join(toolDir, "ffprobe"), []byte(script), 0o700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", toolDir+string(os.PathListSeparator)+os.Getenv("PATH"))
	worker := probe.Worker{Queue: q, Parameters: probe.Parameters(10)}

	os.WriteFile(filepath.Join(root, "new.mp4"), []byte("new"), 0o600)
	s.onStep = func(at string) error {
		if at == "publish" {
			return worker.Run(t.Context(), j)
		}
		return nil
	}
	if e = s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	s.onStep = nil
	before, e := i.Directory(t.Context(), 1)
	if e != nil {
		t.Fatal(e)
	}
	if moved, e := i.PublishProbedTimes(t.Context()); e != nil || moved != 1 {
		t.Fatal("probed capture time was not published once", moved, e)
	}
	after, e := i.Directory(t.Context(), 1)
	if e != nil || after.Revision != before.Revision+1 {
		t.Fatal("reorder did not change the directory revision exactly once", before.Revision, after.Revision, e)
	}
	current, e := i.Media(t.Context(), j.MediaID)
	if e != nil || current.EffectiveTime != time.Date(2001, 1, 1, 0, 0, 0, 0, time.UTC).UnixNano() || current.Width != 1920 {
		t.Fatal("publication overwrote probe result", e, current)
	}
	if moved, e := i.PublishProbedTimes(t.Context()); e != nil || moved != 0 {
		t.Fatal("published capture times were applied twice", moved, e)
	}
}

func TestDurableTaskDueRetryAndObsoleteVersion(t *testing.T) {
	s, root := fixture(t)
	i := s.Index
	os.WriteFile(filepath.Join(root, "a.mp4"), []byte("first"), 0o600)
	now := time.Now()
	s.now = func() time.Time { return now }
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	q := jobs.NewQueue(i, nil, func() time.Time { return now })
	if _, e := q.Claim(t.Context(), "probe"); !errors.Is(e, sql.ErrNoRows) {
		t.Fatal("probe ran before 60 seconds", e)
	}
	now = now.Add(60 * time.Second)
	j, e := q.Claim(t.Context(), "probe")
	if e != nil {
		t.Fatal(e)
	}
	if e = q.Finish(t.Context(), j, "retry", "fixture"); e != nil {
		t.Fatal(e)
	}
	if _, e = q.Claim(t.Context(), "probe"); !errors.Is(e, sql.ErrNoRows) {
		t.Fatal("retry ran before delay")
	}
	for range 2 {
		now = now.Add(30 * time.Second)
		j, e = q.Claim(t.Context(), "probe")
		if e != nil {
			t.Fatal(e)
		}
		if e = q.Finish(t.Context(), j, "retry", "fixture"); e != nil {
			t.Fatal(e)
		}
	}
	var state string
	var attempts int
	if e = i.Store.QueryRow(t.Context(), "SELECT state,attempts FROM jobs WHERE id=?", j.ID).Scan(&state, &attempts); e != nil {
		t.Fatal(e)
	}
	if state != "failed" || attempts != 3 {
		t.Fatalf("%s/%d", state, attempts)
	}
	var status string
	if e = i.Store.QueryRow(t.Context(), "SELECT status FROM versions WHERE media_id=? AND version=?", j.MediaID, j.Version).Scan(&status); e != nil || status != "failed" {
		t.Fatal("exhausted job left its version waiting", status, e)
	}
	os.WriteFile(filepath.Join(root, "a.mp4"), []byte("second version"), 0o600)
	if e = s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	now = now.Add(60 * time.Second)
	newJob, e := q.Claim(t.Context(), "probe")
	if e != nil {
		t.Fatal(e)
	}
	if newJob.Version == j.Version {
		t.Fatal("obsolete job reclaimed")
	}
}

func TestDirectoryAbsenceInterruptedByFailedRoot(t *testing.T) {
	s, root := fixture(t)
	i := s.Index
	sub := filepath.Join(root, "child")
	os.Mkdir(sub, 0o700)
	os.WriteFile(filepath.Join(sub, "a.mp4"), []byte("fixture"), 0o600)
	if e := s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	ls, _ := i.Libraries(t.Context())
	children, e := i.Children(t.Context(), ls[0].DirectoryID, "", false)
	if e != nil || len(children.Items) != 1 {
		t.Fatal(e)
	}
	id := children.Items[0].ID
	os.RemoveAll(sub)
	if e = s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	os.WriteFile(filepath.Join(root, "fault.mp4"), []byte("fixture"), 0o600)
	s.stat = func(*os.File, string) (os.FileInfo, error) { return nil, errors.New("permission denied") }
	if e = s.Run(t.Context(), false); e == nil {
		t.Fatal("fault lost")
	}
	os.Remove(filepath.Join(root, "fault.mp4"))
	s.stat = mediafs.StatAt
	if e = s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	d, e := i.Directory(t.Context(), id)
	if e != nil || d.State != "visible" {
		t.Fatal("directory hidden across failed root", e, d.State)
	}
	if e = s.Run(t.Context(), false); e != nil {
		t.Fatal(e)
	}
	d, e = i.Directory(t.Context(), id)
	if e != nil || d.State != "missing" {
		t.Fatal("directory not hidden after two healthy rounds", e, d.State)
	}
}

func TestUnavailableAncestorAndUnpublishedJobsRemainRecoverable(t *testing.T) {
	s, root := fixture(t)
	i := s.Index
	nested := filepath.Join(root, "parent", "child")
	if err := os.MkdirAll(nested, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(nested, "a.mp4"), []byte("fixture"), 0o600); err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	s.now = func() time.Time { return now }
	if err := s.Run(t.Context(), false); err != nil {
		t.Fatal(err)
	}
	var parent int64
	if err := i.Store.QueryRow(t.Context(), "SELECT id FROM directories WHERE relative='parent'").Scan(&parent); err != nil {
		t.Fatal(err)
	}
	now = now.Add(time.Minute)
	q := jobs.NewQueue(i, nil, func() time.Time { return now })
	for _, state := range []string{"missing", "ignored"} {
		if err := i.Store.Write(t.Context(), 0, func(tx *sql.Tx) error {
			_, err := tx.ExecContext(t.Context(), "UPDATE directories SET state=? WHERE id=?", state, parent)
			return err
		}); err != nil {
			t.Fatal(err)
		}
		commits := i.Store.Metrics.Commits.Load()
		if _, err := q.Claim(t.Context(), "probe"); !errors.Is(err, sql.ErrNoRows) {
			t.Fatal("claimed through unavailable ancestor", state, err)
		}
		if i.Store.Metrics.Commits.Load() != commits {
			t.Fatal("empty candidate search acquires writer")
		}
	}
	if err := i.Store.Write(t.Context(), 0, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(t.Context(), "UPDATE directories SET state='visible' WHERE id=?", parent)
		return err
	}); err != nil {
		t.Fatal(err)
	}
	j, err := q.Claim(t.Context(), "probe")
	if err != nil || j.Attempts != 1 {
		t.Fatal("reappearance lost task or consumed retries", err, j)
	}
	if err = q.Finish(t.Context(), j, "done", ""); err != nil {
		t.Fatal(err)
	}
	libs, err := i.Libraries(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	d, err := i.Directory(t.Context(), libs[0].DirectoryID)
	if err != nil {
		t.Fatal(err)
	}
	gen, err := i.BeginBuild(t.Context(), d)
	if err != nil {
		t.Fatal(err)
	}
	// Due but unpublished jobs can be numerous. An empty claim must not search
	// them while owning the single formal writer.
	n := 257
	if raw := os.Getenv("VV_JOB_ITEMS"); raw != "" {
		n, err = strconv.Atoi(raw)
		if err != nil || n < 1 || n > 510000 {
			t.Fatal("invalid job scale")
		}
	}
	for start := 0; start < n; start += 128 {
		batch := make([]library.Entry, 0, 128)
		for index := start; index < min(n, start+128); index++ {
			batch = append(batch, library.Entry{ID: uuid.New().String(), Version: uuid.New().String(), Name: fmt.Sprintf("candidate-%08d.mp4", index), Kind: "video", Size: 1, Fingerprint: fmt.Sprint(index), EffectiveTime: 1, Mtime: 1, NewVersion: true, Present: true, State: "visible"})
		}
		if err = i.AddBatch(t.Context(), library.GenerationBatch{Directory: d, Generation: gen, Entries: batch, ObservedAt: now.Add(-time.Minute)}); err != nil {
			t.Fatal(err)
		}
	}
	commits := i.Store.Metrics.Commits.Load()
	began := time.Now()
	if _, err = q.Claim(t.Context(), "probe"); !errors.Is(err, sql.ErrNoRows) {
		t.Fatal("unpublished job claimed", err)
	}
	t.Logf("unpublished_candidates=%d read_search=%s writer_commits=%d", n, time.Since(began), i.Store.Metrics.Commits.Load()-commits)
	if i.Store.Metrics.Commits.Load() != commits {
		t.Fatal("unpublished search acquired writer")
	}
	if err = i.Publish(t.Context(), library.Publication{Directory: d, Generation: gen, Identity: "fixture", Round: 1, Validate: func() error { return nil }}); err != nil {
		t.Fatal(err)
	}
	if _, err = q.Claim(t.Context(), "probe"); err != nil {
		t.Fatal("published job remained unavailable", err)
	}
}
