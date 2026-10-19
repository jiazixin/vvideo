package library

import (
	"context"
	"database/sql"
	"errors"
	"path/filepath"
	"slices"
	"time"
	"uuid"

	"vvideo/internal/config"
	"vvideo/internal/storage"
)

var (
	ErrConflict    = errors.New("directory revision changed; relocate by anchor")
	ErrUnavailable = errors.New("media or directory unavailable")
	// ErrInvalid marks malformed client input such as a bad cursor or sort.
	ErrInvalid = errors.New("invalid request")
)

// maxDepth bounds ancestor walks so a corrupted parent chain cannot loop.
const maxDepth = 1024

type Library struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Root        string `json:"-"`
	Identity    string `json:"-"`
	DirectoryID int64  `json:"directory_id"`
	Healthy     bool   `json:"healthy"`
}
type Directory struct {
	ID         int64  `json:"id"`
	LibraryID  string `json:"library_id"`
	ParentID   int64  `json:"parent_id"`
	Relative   string `json:"-"`
	Name       string `json:"name"`
	Identity   string `json:"-"`
	Generation int64  `json:"generation"`
	Revision   int64  `json:"revision"`
	State      string `json:"state"`
}

// Entry holds one immutable file version and its published directory membership.
type Entry struct {
	ID                                         string `json:"id"`
	Version                                    string `json:"version"`
	DirectoryID                                int64  `json:"directory_id"`
	LibraryID                                  string `json:"library_id"`
	Name                                       string `json:"name"`
	Kind                                       string `json:"kind"`
	Size                                       int64  `json:"size"`
	Mtime                                      int64  `json:"mtime"`
	Fingerprint                                string `json:"-"`
	Width                                      int    `json:"width"`
	Height                                     int    `json:"height"`
	Rotation                                   int    `json:"rotation"`
	DurationMS                                 int64  `json:"duration_ms"`
	EffectiveTime                              int64  `json:"effective_time"`
	CapturedRaw                                string `json:"captured_raw"`
	CapturedSource                             string `json:"captured_source"`
	Status                                     string `json:"status"`
	Error                                      string `json:"error"`
	State                                      string `json:"state"`
	Present                                    bool   `json:"-"`
	MissingStreak, MissingEpoch, ObservedRound int64  `json:"-"`
	NewVersion                                 bool   `json:"-"`
}
type Index struct{ Store *storage.Store }

func (i *Index) Register(ctx context.Context, c config.Config) error {
	return i.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, "UPDATE libraries SET active=0"); err != nil {
			return err
		}
		for _, p := range c.Media {
			id := uuid.New().String()
			if _, err := tx.ExecContext(ctx, "INSERT INTO libraries(id,root,name) VALUES(?,?,?) ON CONFLICT(root) DO UPDATE SET active=1", id, p.Path, filepath.Base(p.Path)); err != nil {
				return err
			}
			if err := tx.QueryRowContext(ctx, "SELECT id FROM libraries WHERE root=?", p.Path).Scan(&id); err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, "INSERT INTO directories(library_id,relative,name) VALUES(?,'.',?) ON CONFLICT(library_id,relative) DO NOTHING", id, filepath.Base(p.Path)); err != nil {
				return err
			}
		}
		return nil
	})
}
func (i *Index) Libraries(ctx context.Context) ([]Library, error) {
	rows, err := i.Store.Reader.QueryContext(ctx, "SELECT l.id,l.name,l.root,l.identity,d.id,l.healthy FROM libraries l JOIN directories d ON d.library_id=l.id AND d.relative='.' WHERE l.active=1 ORDER BY l.name,l.id LIMIT 200")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Library{}
	for rows.Next() {
		var l Library
		if err = rows.Scan(&l.ID, &l.Name, &l.Root, &l.Identity, &l.DirectoryID, &l.Healthy); err != nil {
			return nil, err
		}
		out = append(out, l)
	}
	return out, rows.Err()
}
func (i *Index) Directory(ctx context.Context, id int64) (Directory, error) {
	var d Directory
	err := i.Store.Reader.QueryRowContext(ctx, "SELECT id,library_id,coalesce(parent_id,0),relative,name,identity,generation,revision,state FROM directories WHERE id=?", id).Scan(&d.ID, &d.LibraryID, &d.ParentID, &d.Relative, &d.Name, &d.Identity, &d.Generation, &d.Revision, &d.State)
	return d, err
}
func (i *Index) Visible(ctx context.Context, id int64) error {
	for depth := 0; id != 0; depth++ {
		if depth >= maxDepth {
			return ErrUnavailable
		}
		d, err := i.Directory(ctx, id)
		if err != nil {
			return err
		}
		if d.State != "visible" {
			return ErrUnavailable
		}
		if d.ParentID == 0 {
			var active bool
			if err := i.Store.Reader.QueryRowContext(ctx, "SELECT active FROM libraries WHERE id=?", d.LibraryID).Scan(&active); err != nil {
				return err
			}
			if !active {
				return ErrUnavailable
			}
		}
		id = d.ParentID
	}
	return nil
}

// Path returns the directories from the library root down to id, after
// checking that the whole chain is visible.
func (i *Index) Path(ctx context.Context, id int64) ([]Directory, error) {
	if err := i.Visible(ctx, id); err != nil {
		return nil, err
	}
	path := []Directory{}
	for id != 0 {
		if len(path) >= maxDepth {
			return nil, ErrUnavailable
		}
		d, err := i.Directory(ctx, id)
		if err != nil {
			return nil, err
		}
		path = append(path, d)
		id = d.ParentID
	}
	slices.Reverse(path)
	return path, nil
}

func (i *Index) Child(ctx context.Context, parent Directory, relative, name, identity string, round int64) (Directory, error) {
	var id int64
	err := i.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, "INSERT INTO directories(library_id,parent_id,relative,name,identity,observed_round) VALUES(?,?,?,?,?,?) ON CONFLICT(library_id,relative) DO UPDATE SET observed_round=excluded.observed_round,state='visible',missing_streak=0", parent.LibraryID, parent.ID, relative, name, identity, round); err != nil {
			return err
		}
		return tx.QueryRowContext(ctx, "SELECT id FROM directories WHERE library_id=? AND relative=?", parent.LibraryID, relative).Scan(&id)
	})
	if err != nil {
		return Directory{}, err
	}
	return i.Directory(ctx, id)
}

const entryColumns = `e.media_id,e.version,e.directory_id,d.library_id,e.name,e.kind,v.size,e.mtime,v.fingerprint,v.width,v.height,v.rotation,v.duration_ms,e.effective_time,v.captured_raw,v.captured_source,v.status,v.error,e.state,e.present,e.missing_streak,e.missing_epoch,e.observed_round`

func ScanEntry(row interface{ Scan(...any) error }) (Entry, error) {
	var e Entry
	err := row.Scan(&e.ID, &e.Version, &e.DirectoryID, &e.LibraryID, &e.Name, &e.Kind, &e.Size, &e.Mtime, &e.Fingerprint, &e.Width, &e.Height, &e.Rotation, &e.DurationMS, &e.EffectiveTime, &e.CapturedRaw, &e.CapturedSource, &e.Status, &e.Error, &e.State, &e.Present, &e.MissingStreak, &e.MissingEpoch, &e.ObservedRound)
	return e, err
}
func (i *Index) Baseline(ctx context.Context, d Directory, after string) ([]Entry, error) {
	rows, err := i.Store.Reader.QueryContext(ctx, `SELECT `+entryColumns+` FROM entries e JOIN directories d ON d.id=e.directory_id JOIN versions v ON v.media_id=e.media_id AND v.version=e.version WHERE e.directory_id=? AND e.generation=? AND e.media_id>? ORDER BY e.media_id LIMIT 128`, d.ID, d.Generation, after)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Entry{}
	for rows.Next() {
		e, err := ScanEntry(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}
func (i *Index) Media(ctx context.Context, id string) (Entry, error) {
	e, err := ScanEntry(i.Store.Reader.QueryRowContext(ctx, `SELECT `+entryColumns+` FROM media m JOIN directories d ON d.id=m.directory_id JOIN libraries l ON l.id=d.library_id JOIN entries e ON e.media_id=m.id AND e.directory_id=d.id AND e.generation=d.generation JOIN versions v ON v.media_id=e.media_id AND v.version=e.version WHERE m.id=? AND l.active=1 AND e.state='visible'`, id))
	if err != nil {
		return e, err
	}
	return e, i.Visible(ctx, e.DirectoryID)
}
func (i *Index) BeginBuild(ctx context.Context, d Directory) (int64, error) {
	gen := d.Generation + 1
	err := i.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, "INSERT INTO builds(directory_id,generation,base_generation,base_revision) VALUES(?,?,?,?)", d.ID, gen, d.Generation, d.Revision)
		return err
	})
	return gen, err
}
func (i *Index) AddBatch(ctx context.Context, d Directory, gen int64, entries []Entry, now time.Time) error {
	if len(entries) > storage.BatchSize {
		return errors.New("build batch exceeds limit")
	}
	var business int64
	for _, e := range entries {
		if e.NewVersion {
			business++
		}
	}
	return i.Store.Write(ctx, business, func(tx *sql.Tx) error {
		entry, err := tx.PrepareContext(ctx, `INSERT INTO entries(directory_id,generation,media_id,version,name,kind,effective_time,mtime,state,present,missing_streak,missing_epoch,observed_round) VALUES(?,?,?,?,?,?,(SELECT effective_time FROM versions WHERE media_id=? AND version=?),?,?,?,?,?,?)`)
		if err != nil {
			return err
		}
		defer entry.Close()
		media, err := tx.PrepareContext(ctx, `INSERT INTO media(id,directory_id,name) VALUES(?,?,?) ON CONFLICT(directory_id,name) DO NOTHING`)
		if err != nil {
			return err
		}
		defer media.Close()
		version, err := tx.PrepareContext(ctx, `INSERT INTO versions(media_id,version,fingerprint,size,mtime,kind,effective_time) VALUES(?,?,?,?,?,?,?)`)
		if err != nil {
			return err
		}
		defer version.Close()
		job, err := tx.PrepareContext(ctx, `INSERT INTO jobs(media_id,version,kind,parameters,due,first_observed,fingerprint) VALUES(?,?,'probe','',?,?,?) ON CONFLICT(media_id,version,kind,parameters) DO NOTHING`)
		if err != nil {
			return err
		}
		defer job.Close()
		for _, e := range entries {
			if e.NewVersion {
				if _, err = media.ExecContext(ctx, e.ID, d.ID, e.Name); err != nil {
					return err
				}
				if _, err = version.ExecContext(ctx, e.ID, e.Version, e.Fingerprint, e.Size, e.Mtime, e.Kind, e.EffectiveTime); err != nil {
					return err
				}
				if _, err = job.ExecContext(ctx, e.ID, e.Version, now.Add(60*time.Second).UnixMilli(), now.UnixMilli(), e.Fingerprint); err != nil {
					return err
				}
			}
			if _, err = entry.ExecContext(ctx, d.ID, gen, e.ID, e.Version, e.Name, e.Kind, e.ID, e.Version, e.Mtime, e.State, e.Present, e.MissingStreak, e.MissingEpoch, e.ObservedRound); err != nil {
				return err
			}
		}
		return nil
	})
}
func (i *Index) Publish(ctx context.Context, d Directory, gen int64, identity string, round int64, validate func() error) error {
	if err := validate(); err != nil {
		return err
	} // filesystem work precedes the writer.
	return i.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		var base int64
		if err := tx.QueryRowContext(ctx, "SELECT generation FROM directories WHERE id=?", d.ID).Scan(&base); err != nil {
			return err
		}
		if base != d.Generation {
			return ErrConflict
		}
		// Each build batch reads the version's current sort key in its writer
		// transaction. Probe completion also updates unpublished memberships.
		// Publication therefore needs no directory-size UPDATE / comparison.
		if _, err := tx.ExecContext(ctx, "UPDATE directories SET generation=?,revision=revision+1,identity=?,observed_round=?,state='visible',missing_streak=0 WHERE id=?", gen, identity, round, d.ID); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, "DELETE FROM builds WHERE directory_id=? AND generation=?", d.ID, gen)
		return err
	})
}

// staleMemberships is true for a directory row d that still has unpublished
// or superseded memberships. Each branch is a primary-key range probe.
const staleMemberships = `(EXISTS(SELECT 1 FROM entries WHERE directory_id=d.id AND generation<d.generation)
 OR EXISTS(SELECT 1 FROM entries WHERE directory_id=d.id AND generation>d.generation)
 OR EXISTS(SELECT 1 FROM builds WHERE directory_id=d.id))`

// Cleanup removes only unpublished/old memberships in bounded, revalidated
// transactions. Version/history records remain available for diagnosis. A
// read-only check first keeps unchanged directories from costing commits.
func (i *Index) Cleanup(ctx context.Context, id int64) error {
	var stale bool
	err := i.Store.Reader.QueryRowContext(ctx, "SELECT "+staleMemberships+" FROM directories d WHERE d.id=?", id).Scan(&stale)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil || !stale {
		return err
	}
	for {
		n := int64(0)
		err := i.Store.Write(ctx, 0, func(tx *sql.Tx) error {
			res, err := tx.ExecContext(ctx, `DELETE FROM entries WHERE (directory_id,generation,media_id) IN (SELECT e.directory_id,e.generation,e.media_id FROM entries e JOIN directories d ON d.id=e.directory_id WHERE e.directory_id=? AND e.generation!=d.generation LIMIT 128)`, id)
			if err != nil {
				return err
			}
			n, err = res.RowsAffected()
			return err
		})
		if err != nil {
			return err
		}
		if n == 0 {
			break
		}
	}
	return i.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, "DELETE FROM builds WHERE directory_id=?", id)
		return err
	})
}

// Recover finishes work interrupted by a crash: it cleans directories with
// stale memberships and requeues running jobs.
func (i *Index) Recover(ctx context.Context) error {
	var after int64
	for {
		rows, err := i.Store.Reader.QueryContext(ctx, "SELECT d.id FROM directories d WHERE d.id>? AND "+staleMemberships+" ORDER BY d.id LIMIT 128", after)
		if err != nil {
			return err
		}
		ids := []int64{}
		for rows.Next() {
			var id int64
			if err = rows.Scan(&id); err != nil {
				rows.Close()
				return err
			}
			ids = append(ids, id)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return err
		}
		if len(ids) == 0 {
			break
		}
		for _, id := range ids {
			if err = i.Cleanup(ctx, id); err != nil {
				return err
			}
			after = id
		}
	}
	for {
		var changed int64
		if err := i.Store.Write(ctx, 0, func(tx *sql.Tx) error {
			result, err := tx.ExecContext(ctx, "UPDATE jobs SET state='queued',lease=0 WHERE id IN (SELECT id FROM jobs WHERE state='running' LIMIT 128)")
			if err != nil {
				return err
			}
			changed, err = result.RowsAffected()
			return err
		}); err != nil {
			return err
		}
		if changed == 0 {
			return nil
		}
	}
}

func (i *Index) ResolveID(ctx context.Context, directory int64, name string) (string, error) {
	var id string
	err := i.Store.Reader.QueryRowContext(ctx, "SELECT id FROM media WHERE directory_id=? AND name=?", directory, name).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return uuid.New().String(), nil
	}
	return id, err
}
