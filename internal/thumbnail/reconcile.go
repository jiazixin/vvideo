package thumbnail

import (
	"context"
	"database/sql"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"
	"vvideo/internal/storage"
)

// Reconcile is one bounded background pass. Request-time statistics never walk
// the cache directory. Only this service's hex-named covers and old temp files
// are reclaimed; publication/deletion guards prevent races with current work.
func (e *Engine) Reconcile(ctx context.Context, pause func(context.Context) error) error {
	directory, err := os.Open(e.Config.Cache.Path)
	if err != nil {
		return err
	}
	defer directory.Close()
	for {
		if err = pause(ctx); err != nil {
			return err
		}
		items, readErr := directory.ReadDir(storage.BatchSize)
		for _, item := range items {
			if item.IsDir() || item.Type()&os.ModeSymlink != 0 {
				continue
			}
			name := item.Name()
			path := filepath.Join(e.Config.Cache.Path, name)
			if strings.HasPrefix(name, ".frame-") && strings.HasSuffix(name, ".webp") {
				info, err := item.Info()
				if err == nil && time.Since(info.ModTime()) > 3*time.Minute {
					if err = os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
						return err
					}
				}
				continue
			}
			key, ok := strings.CutSuffix(name, ".webp")
			if !ok || len(key) != 64 || strings.Trim(key, "0123456789abcdef") != "" {
				continue
			}
			// budget serializes ledger checks with final publication and eviction.
			e.budget.Lock()
			var exists int
			err = e.Queue.Store.Reader.QueryRowContext(ctx, "SELECT 1 FROM thumbnails WHERE key=?", key).Scan(&exists)
			if errors.Is(err, sql.ErrNoRows) {
				e.mu.Lock()
				busy := e.pins[key] > 0 || e.deleting[key]
				e.mu.Unlock()
				if !busy {
					err = os.Remove(path)
					if errors.Is(err, os.ErrNotExist) {
						err = nil
					}
				} else {
					err = nil
				}
			}
			e.budget.Unlock()
			if err != nil {
				return err
			}
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
		if readErr != nil {
			return readErr
		}
	}
	cursor := ""
	for {
		if err = pause(ctx); err != nil {
			return err
		}
		rows, err := e.Queue.Store.Reader.QueryContext(ctx, "SELECT key,size FROM thumbnails WHERE key>? ORDER BY key LIMIT 128", cursor)
		if err != nil {
			return err
		}
		type entry struct {
			key  string
			size int64
		}
		batch := []entry{}
		for rows.Next() {
			var x entry
			if err = rows.Scan(&x.key, &x.size); err != nil {
				rows.Close()
				return err
			}
			batch = append(batch, x)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return err
		}
		if len(batch) == 0 {
			return nil
		}
		for _, x := range batch {
			cursor = x.key
			e.budget.Lock()
			_, err = os.Stat(filepath.Join(e.Config.Cache.Path, x.key+".webp"))
			if errors.Is(err, os.ErrNotExist) {
				err = e.Queue.Store.Write(ctx, 0, func(tx *sql.Tx) error {
					r, err := tx.ExecContext(ctx, "DELETE FROM thumbnails WHERE key=?", x.key)
					if err != nil {
						return err
					}
					n, err := r.RowsAffected()
					if err != nil {
						return err
					}
					if n > 0 {
						_, err = tx.ExecContext(ctx, "UPDATE counters SET value=max(0,value-?) WHERE name='thumbnail_bytes'", x.size)
					}
					return err
				})
			}
			e.budget.Unlock()
			if err != nil {
				return err
			}
		}
	}
}
