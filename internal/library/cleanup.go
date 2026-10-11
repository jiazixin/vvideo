package library

import (
	"context"
	"database/sql"
)

// CleanupVersions discards obsolete metadata and jobs only after old directory
// memberships are gone. Stable media IDs remain for path reappearance. Covers
// keep their version record until the LRU removes the corresponding file.
func (i *Index) CleanupVersions(ctx context.Context, directory int64) error {
	afterID, afterVersion := "", ""
	for {
		rows, err := i.Store.Reader.QueryContext(ctx, `SELECT v.media_id,v.version FROM media m JOIN versions v ON v.media_id=m.id WHERE m.directory_id=? AND (v.media_id,v.version)>(?,?) AND NOT EXISTS(SELECT 1 FROM entries e WHERE e.media_id=v.media_id AND e.version=v.version) AND NOT EXISTS(SELECT 1 FROM thumbnails t WHERE t.media_id=v.media_id AND t.version=v.version) AND NOT EXISTS(SELECT 1 FROM jobs j WHERE j.media_id=v.media_id AND j.version=v.version AND j.state='running') ORDER BY v.media_id,v.version LIMIT 128`, directory, afterID, afterVersion)
		if err != nil {
			return err
		}
		type version struct{ id, value string }
		batch := []version{}
		for rows.Next() {
			var v version
			if err = rows.Scan(&v.id, &v.value); err != nil {
				rows.Close()
				return err
			}
			batch = append(batch, v)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return err
		}
		if len(batch) == 0 {
			return nil
		}
		if err = i.Store.Write(ctx, 0, func(tx *sql.Tx) error {
			for _, v := range batch {
				// Revalidate ownership in the writer: an in-flight candidate or task may
				// have appeared after the reader finished materializing this small page.
				var used bool
				if err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM entries WHERE media_id=? AND version=?) OR EXISTS(SELECT 1 FROM thumbnails WHERE media_id=? AND version=?) OR EXISTS(SELECT 1 FROM jobs WHERE media_id=? AND version=? AND state='running')`, v.id, v.value, v.id, v.value, v.id, v.value).Scan(&used); err != nil {
					return err
				}
				if used {
					continue
				}
				if _, err := tx.ExecContext(ctx, "DELETE FROM jobs WHERE media_id=? AND version=?", v.id, v.value); err != nil {
					return err
				}
				if _, err := tx.ExecContext(ctx, "DELETE FROM versions WHERE media_id=? AND version=?", v.id, v.value); err != nil {
					return err
				}
			}
			return nil
		}); err != nil {
			return err
		}
		last := batch[len(batch)-1]
		afterID, afterVersion = last.id, last.value
	}
}
