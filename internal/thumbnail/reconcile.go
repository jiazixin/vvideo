package thumbnail

import (
	"context"
	"database/sql"
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"time"

	"vvideo/internal/storage"
)

// staleTempAge is how old an unfinished FFmpeg output must be before
// Reconcile deletes it; it exceeds coverTimeout so running jobs keep theirs.
const staleTempAge = 3 * time.Minute

// Reconcile is one bounded background pass. Request-time statistics never walk
// the cache directory. Only this service's hex-named covers and old temp files
// are reclaimed; publication/deletion guards prevent races with current work.
func (e *Engine) Reconcile(ctx context.Context) error {
	if err := e.removeOrphanFiles(ctx); err != nil {
		return err
	}
	return e.forgetMissingFiles(ctx)
}

// removeOrphanFiles deletes stale FFmpeg temp files and covers the ledger does
// not know, one directory page at a time.
func (e *Engine) removeOrphanFiles(ctx context.Context) error {
	directory, err := os.Open(e.Config.Cache.Path)
	if err != nil {
		return err
	}
	defer directory.Close()
	for {
		if err = ctx.Err(); err != nil {
			return err
		}
		items, readErr := directory.ReadDir(storage.BatchSize)
		for _, item := range items {
			if item.IsDir() || item.Type()&os.ModeSymlink != 0 {
				continue
			}
			if err = e.removeIfOrphan(ctx, item); err != nil {
				return err
			}
		}
		if errors.Is(readErr, io.EOF) {
			return nil
		}
		if readErr != nil {
			return readErr
		}
	}
}

// removeIfOrphan deletes item if it is an FFmpeg temp file older than
// staleTempAge, or a cover with no ledger row that no request is using.
func (e *Engine) removeIfOrphan(ctx context.Context, item fs.DirEntry) error {
	name := item.Name()
	path := filepath.Join(e.Config.Cache.Path, name)
	if strings.HasPrefix(name, ".frame-") && strings.HasSuffix(name, ".webp") {
		info, err := item.Info()
		if err != nil || time.Since(info.ModTime()) <= staleTempAge {
			return nil
		}
		return removeFile(path)
	}
	key, ok := strings.CutSuffix(name, ".webp")
	if !ok || len(key) != 64 || strings.Trim(key, "0123456789abcdef") != "" {
		return nil
	}
	// budget serializes ledger checks with final publication and eviction.
	e.budget.Lock()
	defer e.budget.Unlock()
	var exists int
	err := e.Queue.Store.Reader.QueryRowContext(ctx, "SELECT 1 FROM thumbnails WHERE key=?", key).Scan(&exists)
	switch {
	case err == nil: // the ledger knows this cover
		return nil
	case !errors.Is(err, sql.ErrNoRows):
		return err
	case e.busy(key):
		return nil
	}
	return removeFile(path)
}

// forgetMissingFiles removes ledger rows whose cover file is gone.
func (e *Engine) forgetMissingFiles(ctx context.Context) error {
	type entry struct {
		key  string
		size int64
	}
	cursor := ""
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		rows, err := e.Queue.Store.Reader.QueryContext(ctx, "SELECT key,size FROM thumbnails WHERE key>? ORDER BY key LIMIT ?", cursor, storage.BatchSize)
		batch, err := storage.Collect(rows, err, func(rows *sql.Rows) (entry, error) {
			var x entry
			return x, rows.Scan(&x.key, &x.size)
		})
		if err != nil || len(batch) == 0 {
			return err
		}
		for _, x := range batch {
			cursor = x.key
			e.budget.Lock()
			_, err = os.Stat(filepath.Join(e.Config.Cache.Path, x.key+".webp"))
			if errors.Is(err, os.ErrNotExist) {
				err = e.forget(ctx, x.key, x.size)
			}
			e.budget.Unlock()
			if err != nil {
				return err
			}
		}
	}
}

// removeFile deletes path; a file already gone counts as removed.
func removeFile(path string) error {
	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}
