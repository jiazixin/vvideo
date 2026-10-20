package storage

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"golang.org/x/sys/unix"
	"net/url"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	_ "modernc.org/sqlite"
)

const BatchSize = 128

type Metrics struct{ Commits, Rows, Failed, WaitNS, ExecutionNS, CommitNS, MaxTransactionNS atomic.Int64 }
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
	s, err := open(ctx, path)
	if err != nil {
		return nil, fmt.Errorf("database %s: %w", path, err)
	}
	return s, nil
}

func open(ctx context.Context, path string) (*Store, error) {
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
	if v > schemaVersion {
		return fail(fmt.Errorf("schema version %d is newer than this build supports (%d)", v, schemaVersion))
	}
	if v < 0 {
		return fail(fmt.Errorf("schema version %d is not a vvideo database", v))
	}
	if v == 0 {
		if err = execTx(ctx, w, schemaSQL); err != nil {
			return fail(fmt.Errorf("initialize schema: %w", err))
		}
		v = schemaVersion
	}
	for ; v < schemaVersion; v++ {
		if err = execTx(ctx, w, migrations[v-1]); err != nil {
			return fail(fmt.Errorf("migrate schema from version %d: %w", v, err))
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
	r.SetMaxOpenConns(4)
	r.SetMaxIdleConns(4)
	if err = r.PingContext(ctx); err != nil {
		r.Close()
		return fail(err)
	}
	acquired = true
	return &Store{Reader: r, Writer: w, Path: path, gate: make(chan struct{}, 1), lock: lock}, nil
}

// execTx runs a schema script atomically, including its user_version update.
func execTx(ctx context.Context, w *sql.DB, script string) error {
	tx, err := w.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, script); err != nil {
		return err
	}
	return tx.Commit()
}
func (s *Store) Close() error {
	s.closeOnce.Do(func() { s.closeError = errors.Join(s.Reader.Close(), s.Writer.Close(), s.lock.Close()) })
	return s.closeError
}
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
