package storage

import (
	"context"
	"database/sql"
	"errors"
	"path/filepath"
	"testing"
	"time"
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
