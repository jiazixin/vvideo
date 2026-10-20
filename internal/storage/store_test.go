package storage

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"vvideo/internal/mediafs"
)

func fixture(t *testing.T) *Store {
	t.Helper()
	s, e := Open(t.Context(), filepath.Join(t.TempDir(), "index.sqlite"))
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { s.Close() })
	return s
}
func TestStoreConnectionPolicy(t *testing.T) {
	s := fixture(t)
	held := []*sql.Conn{}
	for range 4 {
		c, e := s.Reader.Conn(t.Context())
		if e != nil {
			t.Fatal(e)
		}
		held = append(held, c)
	}
	defer func() {
		for _, c := range held {
			c.Close()
		}
	}()
	w, e := s.Writer.Conn(t.Context())
	if e != nil {
		t.Fatal(e)
	}
	defer w.Close()
	for _, c := range append(held, w) {
		for key, want := range map[string]string{"journal_mode": "wal", "synchronous": "2", "foreign_keys": "1", "busy_timeout": "5000"} {
			var got string
			if e := c.QueryRowContext(t.Context(), "PRAGMA "+key).Scan(&got); e != nil || got != want {
				t.Fatalf("%s got %s want %s err %v", key, got, want, e)
			}
		}
	}
	if _, e := held[0].ExecContext(t.Context(), "INSERT INTO counters VALUES('forbidden',1)"); e == nil {
		t.Fatal("reader writes allowed")
	}
	if _, e := w.ExecContext(t.Context(), "INSERT INTO counters VALUES('writer',1)"); e != nil {
		t.Fatal(e)
	}
}
func TestBatchCommitAtomicity(t *testing.T) {
	s := fixture(t)
	write := func(first, fail int) error {
		return s.Write(t.Context(), 128, func(tx *sql.Tx) error {
			stmt, e := tx.PrepareContext(t.Context(), "INSERT INTO counters VALUES(?,1)")
			if e != nil {
				return e
			}
			defer stmt.Close()
			for i := range 128 {
				if i == fail {
					return errors.New("injected")
				}
				if _, e = stmt.ExecContext(t.Context(), first+i); e != nil {
					return e
				}
			}
			return nil
		})
	}
	if e := write(0, -1); e != nil {
		t.Fatal(e)
	}
	if e := write(128, 64); e == nil {
		t.Fatal("fault accepted")
	}
	s.Fault = func(string) error { return errors.New("commit fault") }
	if e := write(128, -1); e == nil {
		t.Fatal("commit fault accepted")
	}
	s.Fault = nil
	var n int
	s.Reader.QueryRow("SELECT count(*) FROM counters WHERE name!='thumbnail_bytes'").Scan(&n)
	if n != 128 || s.Metrics.Rows.Load() != 128 {
		t.Fatalf("partial write or counter: %d %d", n, s.Metrics.Rows.Load())
	}
	if e := write(128, -1); e != nil {
		t.Fatal(e)
	}
}
func TestWriterCancellation(t *testing.T) {
	s := fixture(t)
	entered := make(chan struct{})
	release := make(chan struct{})
	done := make(chan error, 1)
	go func() { done <- s.Write(t.Context(), 0, func(*sql.Tx) error { close(entered); <-release; return nil }) }()
	<-entered
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if e := s.Write(ctx, 0, func(*sql.Tx) error { t.Error("cancelled callback ran"); return nil }); !errors.Is(e, context.Canceled) {
		t.Fatal(e)
	}
	close(release)
	if e := <-done; e != nil {
		t.Fatal(e)
	}
	if e := s.Write(t.Context(), 0, func(*sql.Tx) error { return nil }); e != nil {
		t.Fatal(e)
	}
}
func TestReaderSnapshotAndRelease(t *testing.T) {
	s := fixture(t)
	tx, e := s.Reader.BeginTx(t.Context(), &sql.TxOptions{ReadOnly: true})
	if e != nil {
		t.Fatal(e)
	}
	var before int
	tx.QueryRow("SELECT value FROM counters WHERE name='thumbnail_bytes'").Scan(&before)
	ctx, cancel := context.WithTimeout(t.Context(), 2*time.Second)
	defer cancel()
	if e := s.Write(ctx, 0, func(tx *sql.Tx) error {
		_, e := tx.Exec("UPDATE counters SET value=10 WHERE name='thumbnail_bytes'")
		return e
	}); e != nil {
		t.Fatal(e)
	}
	var old, now int
	tx.QueryRow("SELECT value FROM counters WHERE name='thumbnail_bytes'").Scan(&old)
	tx.Rollback()
	s.Reader.QueryRow("SELECT value FROM counters WHERE name='thumbnail_bytes'").Scan(&now)
	if old != before || now != 10 {
		t.Fatal("snapshot broken")
	}
}

func TestSingleServiceOwnsWriterAndCrashLockReleases(t *testing.T) {
	path := filepath.Join(t.TempDir(), "index.sqlite")
	first, err := Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	second, err := Open(t.Context(), path)
	if err == nil {
		second.Close()
		t.Fatal("two global writers accepted")
	}
	if err = first.Close(); err != nil {
		t.Fatal(err)
	}
	second, err = Open(t.Context(), path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { second.Close() })
}

// TestMigrateVersion1 upgrades a version 1 database to the current schema in
// place and matches what a new database gets.
func TestMigrateVersion1(t *testing.T) {
	if len(migrations) != schemaVersion-1 {
		t.Fatalf("%d migrations for schema version %d", len(migrations), schemaVersion)
	}
	path := filepath.Join(t.TempDir(), "index.sqlite")
	s, e := Open(t.Context(), path)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.Writer.ExecContext(t.Context(), "DROP INDEX job_foreground; PRAGMA user_version=1;"); e != nil {
		t.Fatal(e)
	}
	s.Close()
	s, e = Open(t.Context(), path)
	if e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	var v, indexes int
	if e = s.Reader.QueryRowContext(t.Context(), "PRAGMA user_version").Scan(&v); e != nil || v != schemaVersion {
		t.Fatal("version", v, e)
	}
	if e = s.Reader.QueryRowContext(t.Context(), "SELECT count(*) FROM sqlite_schema WHERE type='index' AND name='job_foreground'").Scan(&indexes); e != nil || indexes != 1 {
		t.Fatal("job_foreground missing", indexes, e)
	}
}

// TestMigrateDropsDeviceFromFingerprints: fingerprints stored before version 3
// carry the device number. After the upgrade an unchanged file's stored value
// equals what the scanner now computes, so nothing looks changed.
func TestMigrateDropsDeviceFromFingerprints(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, "a.mp4")
	if e := os.WriteFile(file, []byte("media"), 0600); e != nil {
		t.Fatal(e)
	}
	info, e := os.Stat(file)
	if e != nil {
		t.Fatal(e)
	}
	current := mediafs.Fingerprint(info)
	rest, ok := strings.CutPrefix(current, "f3:")
	if !ok {
		t.Skip("platform has no inode fingerprint")
	}
	legacy := fmt.Sprintf("f2:%d:%s", info.Sys().(*syscall.Stat_t).Dev, rest)
	path := filepath.Join(dir, "index.sqlite")
	s, e := Open(t.Context(), path)
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.Writer.ExecContext(t.Context(), `INSERT INTO libraries(id,root,name) VALUES('l','/m','m');
INSERT INTO directories(id,library_id,relative,name,identity) VALUES(1,'l','.','m',?),(2,'l','x','x','v2:root');
INSERT INTO media(id,directory_id,name) VALUES('m',1,'a.mp4');
INSERT INTO versions(media_id,version,fingerprint,size,mtime,kind,effective_time) VALUES('m','v',?,5,0,'video',0);
INSERT INTO jobs(media_id,version,kind,parameters,due,fingerprint) VALUES('m','v','probe','',0,?);
PRAGMA user_version=2;`, legacy, legacy, legacy); e != nil {
		t.Fatal(e)
	}
	s.Close()
	if s, e = Open(t.Context(), path); e != nil {
		t.Fatal(e)
	}
	defer s.Close()
	for _, q := range []string{"SELECT fingerprint FROM versions", "SELECT fingerprint FROM jobs", "SELECT identity FROM directories WHERE id=1"} {
		var got string
		if e = s.Reader.QueryRowContext(t.Context(), q).Scan(&got); e != nil || got != current {
			t.Errorf("%s: %q, want %q (%v)", q, got, current, e)
		}
	}
	var untouched string
	if e = s.Reader.QueryRowContext(t.Context(), "SELECT identity FROM directories WHERE id=2").Scan(&untouched); e != nil || untouched != "v2:root" {
		t.Errorf("non-fingerprint identity rewritten: %q (%v)", untouched, e)
	}
}
