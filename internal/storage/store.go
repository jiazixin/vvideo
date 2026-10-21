// Package storage owns the SQLite database: exclusive ownership, connection
// settings, schema initialization and the single serialized writer.
package storage

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/sys/unix"
	_ "modernc.org/sqlite"
)

const (
	// BatchSize is the maximum number of rows a scan, cleanup or rebuild step
	// reads or writes per transaction.
	BatchSize = 128
	// readConnections is the size of the read-only connection pool.
	readConnections = 4
)

// Metrics are cumulative writer statistics since the store was opened.
type Metrics struct {
	Commits, Failed atomic.Int64
	// Rows counts the business rows that committed write calls reported.
	Rows atomic.Int64
	// WaitNS, ExecutionNS and CommitNS sum the time spent waiting for the
	// writer, running transaction bodies and committing.
	WaitNS, ExecutionNS, CommitNS atomic.Int64
	MaxTransactionNS              atomic.Int64
}

// Store holds one writer connection and a pool of read-only connections to
// the same WAL database.
type Store struct {
	Reader     *sql.DB
	Writer     *sql.DB
	Path       string
	Metrics    Metrics
	gate       chan struct{}
	lock       *os.File
	closeOnce  sync.Once
	closeError error
	// Fault is a deterministic test seam, configured before concurrent use.
	Fault func(string) error
}

// ErrLocked reports that another process already serves the same database.
var ErrLocked = errors.New("database is in use by another vvideo process")

// Open takes exclusive ownership of the database and initializes its schema.
// Errors name the database path so startup failures are actionable.
func Open(ctx context.Context, path string) (*Store, error) {
	s, err := openStore(ctx, path)
	if err != nil {
		return nil, fmt.Errorf("database %s: %w", path, err)
	}
	return s, nil
}

func openStore(ctx context.Context, path string) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return nil, err
	}
	lockPath := path + ".lock"
	fd, err := unix.Open(lockPath, unix.O_CREAT|unix.O_RDWR|unix.O_CLOEXEC|unix.O_NOFOLLOW, 0600)
	if err != nil {
		return nil, &os.PathError{Op: "open", Path: lockPath, Err: err}
	}
	lock := os.NewFile(uintptr(fd), lockPath)
	if err = unix.Flock(fd, unix.LOCK_EX|unix.LOCK_NB); err != nil {
		lock.Close()
		if errors.Is(err, unix.EWOULDBLOCK) {
			return nil, ErrLocked
		}
		return nil, &os.PathError{Op: "lock", Path: lockPath, Err: err}
	}
	acquired := false
	defer func() {
		if !acquired {
			lock.Close()
		}
	}()
	u := url.URL{Scheme: "file", Path: path}
	q := u.Query()
	q.Add("_pragma", "foreign_keys(1)")
	q.Add("_pragma", "busy_timeout(5000)")
	q.Add("_pragma", "synchronous(FULL)")
	u.RawQuery = q.Encode()
	w, err := sql.Open("sqlite", u.String())
	if err != nil {
		return nil, err
	}
	w.SetMaxOpenConns(1)
	w.SetMaxIdleConns(1)
	fail := func(err error) (*Store, error) { w.Close(); return nil, err }
	var mode string
	if err = w.QueryRowContext(ctx, "PRAGMA journal_mode=WAL").Scan(&mode); err != nil {
		return fail(fmt.Errorf("enable WAL: %w", err))
	}
	if mode != "wal" {
		return fail(fmt.Errorf("filesystem does not support WAL (journal mode %q); keep the database on a local disk", mode))
	}
	var v int
	if err = w.QueryRowContext(ctx, "PRAGMA user_version").Scan(&v); err != nil {
		return fail(fmt.Errorf("read schema version: %w", err))
	}
	if v != 0 && v != schemaVersion {
		return fail(fmt.Errorf("schema version %d does not match this build (%d); there are no migrations before release, so stop the service and delete the storage directory to rebuild the index", v, schemaVersion))
	}
	if v == 0 {
		if err = initSchema(ctx, w); err != nil {
			return fail(fmt.Errorf("initialize schema: %w", err))
		}
	}
	if err = os.Chmod(path, 0600); err != nil {
		return fail(err)
	}
	q.Set("mode", "ro")
	q.Add("_pragma", "query_only(1)")
	u.RawQuery = q.Encode()
	r, err := sql.Open("sqlite", u.String())
	if err != nil {
		return fail(err)
	}
	r.SetMaxOpenConns(readConnections)
	r.SetMaxIdleConns(readConnections)
	if err = r.PingContext(ctx); err != nil {
		r.Close()
		return fail(err)
	}
	acquired = true
	return &Store{Reader: r, Writer: w, Path: path, gate: make(chan struct{}, 1), lock: lock}, nil
}

// initSchema creates the schema and records schemaVersion atomically.
func initSchema(ctx context.Context, w *sql.DB) error {
	tx, err := w.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, schemaSQL); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, fmt.Sprintf("PRAGMA user_version=%d", schemaVersion)); err != nil {
		return err
	}
	return tx.Commit()
}

// Close closes both connection pools and releases the ownership lock.
func (s *Store) Close() error {
	s.closeOnce.Do(func() { s.closeError = errors.Join(s.Reader.Close(), s.Writer.Close(), s.lock.Close()) })
	return s.closeError
}

// Write runs fn in a transaction on the single writer connection and commits
// it. Callers queue for the writer, so fn must not do filesystem or tool work,
// nor call Write itself. rows is the number of business rows fn writes, added
// to Metrics.Rows only when the commit succeeds.
func (s *Store) Write(ctx context.Context, rows int64, fn func(*sql.Tx) error) (err error) {
	wait := time.Now()
	select {
	case s.gate <- struct{}{}:
	case <-ctx.Done():
		return ctx.Err()
	}
	defer func() { <-s.gate }()
	s.Metrics.WaitNS.Add(time.Since(wait).Nanoseconds())
	start := time.Now()
	defer func() {
		if err != nil {
			s.Metrics.Failed.Add(1)
		}
	}()
	tx, err := s.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err = fn(tx); err != nil {
		return err
	}
	s.Metrics.ExecutionNS.Add(time.Since(start).Nanoseconds())
	if s.Fault != nil {
		if err = s.Fault("commit"); err != nil {
			return err
		}
	}
	commit := time.Now()
	if err = tx.Commit(); err != nil {
		return err
	}
	s.Metrics.CommitNS.Add(time.Since(commit).Nanoseconds())
	s.Metrics.Commits.Add(1)
	s.Metrics.Rows.Add(rows)
	n := time.Since(start).Nanoseconds()
	for old := s.Metrics.MaxTransactionNS.Load(); n > old; old = s.Metrics.MaxTransactionNS.Load() {
		if s.Metrics.MaxTransactionNS.CompareAndSwap(old, n) {
			break
		}
	}
	return nil
}
