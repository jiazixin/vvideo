package library

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strconv"
	"uuid"

	"vvideo/internal/storage"
)

// RebuildGroups materializes one anchor per ten media without holding a read
// transaction across writer requests. Candidates are invisible until the final
// revision check. All reads/writes retain at most 128 records.
func (i *Index) RebuildGroups(ctx context.Context, id int64, pause func(context.Context) error) error {
	if err := i.CleanupGroups(ctx, id); err != nil {
		return err
	}
	d, err := i.Directory(ctx, id)
	if err != nil {
		return err
	}
	token := uuid.New().String()
	type anchor struct {
		sort, direction, id, name string
		time                      int64
	}
	pending := []anchor{}
	flush := func() error {
		if len(pending) == 0 {
			return nil
		}
		err := i.Store.Write(ctx, 0, func(tx *sql.Tx) error {
			stmt, err := tx.PrepareContext(ctx, "INSERT INTO group_anchors VALUES(?,?,?,?,?,?,?)")
			if err != nil {
				return err
			}
			defer stmt.Close()
			for _, a := range pending {
				if _, err = stmt.ExecContext(ctx, id, token, a.sort, a.direction, a.time, a.name, a.id); err != nil {
					return err
				}
			}
			return nil
		})
		pending = pending[:0]
		return err
	}
	for _, sort := range []string{"capture", "mtime", "name"} {
		col := map[string]string{"capture": "effective_time", "mtime": "mtime", "name": "name"}[sort]
		for _, dir := range []string{"asc", "desc"} {
			var cursor Cursor
			ordinal := int64(0)
			for {
				if err = pause(ctx); err != nil {
					return err
				}
				filter := ""
				args := []any{id, d.Generation}
				if cursor.ID != "" {
					operator := ">"
					if dir == "desc" {
						operator = "<"
					}
					filter = " AND (" + col + ",media_id)" + operator + "(?,?)"
					args = append(args, cursor.Key, cursor.ID)
				}
				rows, err := i.Store.Reader.QueryContext(ctx, `SELECT media_id,effective_time,mtime,name FROM entries WHERE directory_id=? AND generation=? AND state='visible'`+filter+` ORDER BY `+col+` `+dir+`,media_id `+dir+` LIMIT 128`, args...)
				if err != nil {
					return err
				}
				type item struct {
					id, name       string
					capture, mtime int64
				}
				batch := []item{}
				for rows.Next() {
					var x item
					if err = rows.Scan(&x.id, &x.capture, &x.mtime, &x.name); err != nil {
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
					break
				}
				for _, x := range batch {
					keyTime := x.capture
					keyName := ""
					if sort == "mtime" {
						keyTime = x.mtime
					}
					if sort == "name" {
						keyTime = 0
						keyName = x.name
					}
					if ordinal%10 == 0 {
						pending = append(pending, anchor{sort: sort, direction: dir, id: x.id, time: keyTime, name: keyName})
						if len(pending) == 128 {
							if err = flush(); err != nil {
								return err
							}
						}
					}
					ordinal++
					cursor = Cursor{ID: x.id, Key: fmt.Sprint(keyTime)}
					if sort == "name" {
						cursor.Key = x.name
					}
				}
			}
		}
	}
	if err = flush(); err != nil {
		return err
	}
	if err = i.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, "UPDATE directories SET group_token=?,group_revision=? WHERE id=? AND generation=? AND revision=?", token, d.Revision, id, d.Generation, d.Revision)
		if err != nil {
			return err
		}
		n, err := res.RowsAffected()
		if err != nil {
			return err
		}
		if n == 0 {
			return ErrConflict
		}
		return nil
	}); err != nil {
		return err
	}
	return i.CleanupGroups(ctx, id)
}
func (i *Index) CleanupGroups(ctx context.Context, id int64) error {
	for {
		var n int64
		err := i.Store.Write(ctx, 0, func(tx *sql.Tx) error {
			r, err := tx.ExecContext(ctx, `DELETE FROM group_anchors WHERE (directory_id,token,sort,direction,media_id) IN(SELECT g.directory_id,g.token,g.sort,g.direction,g.media_id FROM group_anchors g JOIN directories d ON d.id=g.directory_id WHERE g.directory_id=? AND g.token!=d.group_token LIMIT 128)`, id)
			if err != nil {
				return err
			}
			n, err = r.RowsAffected()
			return err
		})
		if err != nil {
			return err
		}
		if n == 0 {
			return nil
		}
	}
}

// PublishProbedTimes copies capture times saved by probes into the listed
// sort keys. Each batch changes a directory's order at most once, together with
// its revision, so cursors never silently mix orders. It returns how many
// entries moved.
func (i *Index) PublishProbedTimes(ctx context.Context) (int64, error) {
	var moved int64
	for {
		rows, err := i.Store.Reader.QueryContext(ctx, "SELECT id,media_id,version FROM jobs WHERE kind='probe' AND state='probed' ORDER BY id LIMIT ?", storage.BatchSize)
		if err != nil {
			return moved, err
		}
		type probed struct {
			id             int64
			media, version string
		}
		batch := []probed{}
		for rows.Next() {
			var p probed
			if err = rows.Scan(&p.id, &p.media, &p.version); err != nil {
				rows.Close()
				return moved, err
			}
			batch = append(batch, p)
		}
		err = rows.Err()
		rows.Close()
		if err != nil || len(batch) == 0 {
			return moved, err
		}
		var batchMoved int64
		err = i.Store.Write(ctx, 0, func(tx *sql.Tx) error {
			batchMoved = 0
			directories := map[int64]bool{}
			for _, p := range batch {
				const stale = "FROM entries WHERE media_id=? AND version=? AND effective_time!=(SELECT effective_time FROM versions WHERE media_id=? AND version=?)"
				rows, err := tx.QueryContext(ctx, "SELECT DISTINCT directory_id "+stale, p.media, p.version, p.media, p.version)
				if err != nil {
					return err
				}
				for rows.Next() {
					var id int64
					if err = rows.Scan(&id); err != nil {
						rows.Close()
						return err
					}
					directories[id] = true
				}
				err = rows.Err()
				rows.Close()
				if err != nil {
					return err
				}
				result, err := tx.ExecContext(ctx, "UPDATE entries SET effective_time=(SELECT effective_time FROM versions WHERE media_id=? AND version=?) WHERE rowid IN (SELECT rowid "+stale+")", p.media, p.version, p.media, p.version, p.media, p.version)
				if err != nil {
					return err
				}
				n, err := result.RowsAffected()
				if err != nil {
					return err
				}
				batchMoved += n
				if _, err = tx.ExecContext(ctx, "UPDATE jobs SET state='done' WHERE id=? AND state='probed'", p.id); err != nil {
					return err
				}
			}
			for id := range directories {
				if _, err := tx.ExecContext(ctx, "UPDATE directories SET revision=revision+1 WHERE id=?", id); err != nil {
					return err
				}
			}
			return nil
		})
		if err != nil {
			return moved, err
		}
		moved += batchMoved
	}
}

// DirtyDirectory returns the next directory after the given ID whose album
// anchors are stale, wrapping to the start so every directory gets a turn. It
// returns sql.ErrNoRows when none is stale.
func (i *Index) DirtyDirectory(ctx context.Context, after int64) (int64, error) {
	const next = "SELECT id FROM directories WHERE state='visible' AND group_revision!=revision AND id>? ORDER BY id LIMIT 1"
	var id int64
	err := i.Store.Reader.QueryRowContext(ctx, next, after).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) && after > 0 {
		err = i.Store.Reader.QueryRowContext(ctx, next, 0).Scan(&id)
	}
	return id, err
}
func (i *Index) groupStart(ctx context.Context, tx *sql.Tx, d Directory, o ListOptions, e Entry) (Cursor, error) {
	if o.Kind != "" {
		return Cursor{ID: e.ID, Key: key(e, o.Sort)}, nil
	}
	var token string
	var revision int64
	if err := tx.QueryRowContext(ctx, "SELECT group_token,group_revision FROM directories WHERE id=?", d.ID).Scan(&token, &revision); err != nil {
		return Cursor{}, err
	}
	if revision != d.Revision {
		return Cursor{}, ErrConflict
	}
	col := "time_key"
	direction := "DESC"
	op := "<="
	if o.Direction == "desc" {
		direction = "ASC"
		op = ">="
	}
	if o.Sort == "name" {
		col = "name_key"
	}
	var id, name string
	var value int64
	err := tx.QueryRowContext(ctx, "SELECT media_id,time_key,name_key FROM group_anchors WHERE directory_id=? AND token=? AND sort=? AND direction=? AND ("+col+",media_id)"+op+"(?,?) ORDER BY "+col+" "+direction+",media_id "+direction+" LIMIT 1", d.ID, token, o.Sort, o.Direction, key(e, o.Sort), e.ID).Scan(&id, &value, &name)
	if errors.Is(err, sql.ErrNoRows) {
		return Cursor{}, ErrConflict
	}
	if err != nil {
		return Cursor{}, err
	}
	k := strconv.FormatInt(value, 10)
	if o.Sort == "name" {
		k = name
	}
	return Cursor{ID: id, Key: k}, nil
}
