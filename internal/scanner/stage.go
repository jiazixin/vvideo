package scanner

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/gob"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"uuid"

	"vvideo/internal/config"
	"vvideo/internal/library"
	"vvideo/internal/storage"
)

// stageSchema is the disposable per-scan database. baseline holds the
// directory's published entries, observation both listing passes, merged the
// entries of the next generation, and walk the subdirectories still to visit.
const stageSchema = `
CREATE TABLE baseline(name TEXT PRIMARY KEY,id TEXT UNIQUE,payload BLOB NOT NULL);
CREATE TABLE observation(pass INTEGER,name TEXT,fingerprint TEXT,kind TEXT,size INTEGER,mtime INTEGER,directory INTEGER,PRIMARY KEY(pass,name));
CREATE TABLE merged(id TEXT PRIMARY KEY,payload BLOB NOT NULL);
CREATE TABLE walk(parent INTEGER,name TEXT,relative TEXT,identity TEXT,PRIMARY KEY(parent,name));
`

// stage is a scan's scratch database on local disk. It may use unsafe
// durability settings because it is deleted after the scan and on restart.
type stage struct {
	db   *sql.DB
	path string
}

// newStage opens a stage under the storage directory. The namespace hashes the
// database path, so only stages left by an interrupted scan of this database
// are removed.
func newStage(ctx context.Context, storageDir string) (*stage, error) {
	database := filepath.Join(storageDir, config.DatabaseFile)
	dir := filepath.Join(config.StageDir(storageDir), fmt.Sprintf("%x", sha256.Sum256([]byte(database))))
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	// The database-owner lock excludes another scan for this storage path.
	// Remove only disposable stages in this storage-specific namespace.
	directory, err := os.Open(dir)
	if err != nil {
		return nil, err
	}
	for {
		if ctx.Err() != nil {
			directory.Close()
			return nil, ctx.Err()
		}
		batch, readErr := directory.ReadDir(storage.BatchSize)
		for _, entry := range batch {
			stem, ok := strings.CutSuffix(entry.Name(), ".sqlite")
			if !ok || entry.IsDir() || entry.Type()&os.ModeSymlink != 0 {
				continue
			}
			if _, err := uuid.Parse(stem); err != nil {
				continue
			}
			if err := os.Remove(filepath.Join(dir, entry.Name())); err != nil && !errors.Is(err, os.ErrNotExist) {
				directory.Close()
				return nil, err
			}
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
		if readErr != nil {
			directory.Close()
			return nil, readErr
		}
	}
	directory.Close()
	path := filepath.Join(dir, uuid.New().String()+".sqlite")
	db, err := sql.Open("sqlite", path+"?_pragma=synchronous(OFF)&_pragma=journal_mode(OFF)")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	_, err = db.ExecContext(ctx, stageSchema)
	if err != nil {
		db.Close()
		os.Remove(path)
		return nil, err
	}
	os.Chmod(path, 0600)
	return &stage{db: db, path: path}, nil
}

// Close closes and deletes the stage database.
func (s *stage) Close() error { return errors.Join(s.db.Close(), os.Remove(s.path)) }

// Reset clears everything but the walk table before the next directory.
func (s *stage) Reset(ctx context.Context) error {
	_, err := s.db.ExecContext(ctx, "DELETE FROM baseline;DELETE FROM observation;DELETE FROM merged;")
	return err
}

// baseline stages a batch of the directory's published entries.
func (s *stage) baseline(ctx context.Context, es []library.Entry) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	stmt, err := tx.PrepareContext(ctx, "INSERT INTO baseline(name,id,payload) VALUES(?,?,?)")
	if err != nil {
		return err
	}
	defer stmt.Close()
	for _, e := range es {
		b, err := encodeEntry(e)
		if err != nil {
			return err
		}
		if _, err = stmt.ExecContext(ctx, e.Name, e.ID, b); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// observe stages one page of a listing pass.
func (s *stage) observe(ctx context.Context, pass int, es []Attributes) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	stmt, err := tx.PrepareContext(ctx, "INSERT INTO observation VALUES(?,?,?,?,?,?,?)")
	if err != nil {
		return err
	}
	defer stmt.Close()
	for _, a := range es {
		if _, err = stmt.ExecContext(ctx, pass, a.Name, a.Fingerprint, a.Kind, a.Size, a.Mtime, a.Directory); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// equal reports whether both listing passes saw exactly the same entries.
func (s *stage) equal(ctx context.Context) (bool, error) {
	var n int
	err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM observation a LEFT JOIN observation b ON b.pass=3-a.pass AND b.name=a.name WHERE b.name IS NULL OR a.fingerprint!=b.fingerprint OR a.kind!=b.kind OR a.size!=b.size OR a.mtime!=b.mtime OR a.directory!=b.directory`).Scan(&n)
	return n == 0, err
}

// put stages one entry of the next generation.
func (s *stage) put(ctx context.Context, e library.Entry) error {
	b, err := encodeEntry(e)
	if err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx, "INSERT INTO merged VALUES(?,?)", e.ID, b)
	return err
}

// merge compares the second listing pass with the baseline and stages the
// next generation: unchanged entries keep their version, changed and new
// files get a new one, and baseline entries no longer listed stay as absent
// (or ignored) so missing confirmation can count them. It returns the number
// of changes; zero means the directory needs no new generation.
func (s *stage) merge(ctx context.Context, d library.Directory, ignored func(string) bool, force bool, resolve func(string) (string, error)) (int64, error) {
	known, err := s.mergeBaseline(ctx, d, ignored, force)
	if err != nil {
		return 0, err
	}
	added, err := s.mergeNew(ctx, d, resolve)
	if err != nil {
		return 0, err
	}
	return known + added, nil
}

// mergeBaseline stages every baseline entry as the second pass observed it.
func (s *stage) mergeBaseline(ctx context.Context, d library.Directory, ignored func(string) bool, force bool) (int64, error) {
	var changed int64
	after := ""
	for {
		rows, err := s.db.QueryContext(ctx, "SELECT payload FROM baseline WHERE id>? ORDER BY id LIMIT ?", after, storage.BatchSize)
		batch, err := storage.Collect(rows, err, scanPayload)
		if err != nil || len(batch) == 0 {
			return changed, err
		}
		for _, e := range batch {
			after = e.ID
			next, changes, err := s.observed(ctx, d, e, ignored, force)
			if err != nil {
				return 0, err
			}
			if err = s.put(ctx, next); err != nil {
				return 0, err
			}
			changed += changes
		}
	}
}

// observed is baseline entry e as the second pass found it, and how many
// changes that makes: a new version, and a different state or presence.
func (s *stage) observed(ctx context.Context, d library.Directory, e library.Entry, ignored func(string) bool, force bool) (library.Entry, int64, error) {
	original := e
	var changes int64
	a := Attributes{Name: e.Name}
	err := s.db.QueryRowContext(ctx, "SELECT fingerprint,kind,size,mtime FROM observation WHERE pass=2 AND name=? AND directory=0", e.Name).Scan(&a.Fingerprint, &a.Kind, &a.Size, &a.Mtime)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		e.Present = false
		if ignored(e.Name) {
			e.State = "ignored"
			e.MissingStreak = 0
		}
	case err != nil:
		return e, 0, err
	default:
		e.Present, e.State, e.MissingStreak, e.ObservedRound = true, "visible", 0, 0
		if e.Fingerprint != a.Fingerprint || force {
			e = changedEntry(d, e.ID, a)
			changes++
		}
	}
	if e.State != original.State || e.Present != original.Present {
		changes++
	}
	return e, changes, nil
}

// mergeNew stages a new version for every file of the second pass that is
// not in the baseline.
func (s *stage) mergeNew(ctx context.Context, d library.Directory, resolve func(string) (string, error)) (int64, error) {
	var added int64
	after := ""
	for {
		rows, err := s.db.QueryContext(ctx, `SELECT o.name,o.fingerprint,o.kind,o.size,o.mtime FROM observation o LEFT JOIN baseline b ON b.name=o.name WHERE o.pass=2 AND o.directory=0 AND b.id IS NULL AND o.name>? ORDER BY o.name LIMIT ?`, after, storage.BatchSize)
		batch, err := storage.Collect(rows, err, func(rows *sql.Rows) (Attributes, error) {
			var a Attributes
			return a, rows.Scan(&a.Name, &a.Fingerprint, &a.Kind, &a.Size, &a.Mtime)
		})
		if err != nil || len(batch) == 0 {
			return added, err
		}
		for _, a := range batch {
			after = a.Name
			id, err := resolve(a.Name)
			if err != nil {
				return 0, err
			}
			if err = s.put(ctx, changedEntry(d, id, a)); err != nil {
				return 0, err
			}
			added++
		}
	}
}

// scanPayload decodes one staged entry from a payload column.
func scanPayload(rows *sql.Rows) (library.Entry, error) {
	var b []byte
	var e library.Entry
	if err := rows.Scan(&b); err != nil {
		return e, err
	}
	return e, decodeEntry(b, &e)
}

// changedEntry is a new version of a file in d, with attributes a.
func changedEntry(d library.Directory, id string, a Attributes) library.Entry {
	return library.Entry{ID: id, Version: uuid.New().String(), DirectoryID: d.ID, LibraryID: d.LibraryID, Name: a.Name, Kind: a.Kind, Fingerprint: a.Fingerprint, Size: a.Size, Mtime: a.Mtime, EffectiveTime: a.Mtime, State: "visible", Present: true, NewVersion: true}
}

// merged pages the staged next generation by media ID.
func (s *stage) merged(ctx context.Context, after string) ([]library.Entry, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT payload FROM merged WHERE id>? ORDER BY id LIMIT ?", after, storage.BatchSize)
	return storage.Collect(rows, err, scanPayload)
}

// queueSubdirectories adds the subdirectories of d seen in the second pass
// to the walk, recording each one's relative path and fingerprint.
func (s *stage) queueSubdirectories(ctx context.Context, d library.Directory) error {
	_, err := s.db.ExecContext(ctx, `INSERT INTO walk(parent,name,relative,identity)
		SELECT ?,name,CASE WHEN ?='.' THEN name ELSE ?||'/'||name END,fingerprint
		FROM observation WHERE pass=2 AND directory=1`, d.ID, d.Relative, d.Relative)
	return err
}

// nextSubdirectory returns the first queued subdirectory of parent named after
// after, or sql.ErrNoRows when none is left.
func (s *stage) nextSubdirectory(ctx context.Context, parent int64, after string) (name, relative, identity string, err error) {
	err = s.db.QueryRowContext(ctx, "SELECT name,relative,identity FROM walk WHERE parent=? AND name>? ORDER BY name LIMIT 1", parent, after).Scan(&name, &relative, &identity)
	return name, relative, identity, err
}

// forgetSubdirectories drops parent's walk rows once all were visited. A
// failure only leaves rows behind until the stage is deleted.
func (s *stage) forgetSubdirectories(ctx context.Context, parent int64) {
	s.db.ExecContext(ctx, "DELETE FROM walk WHERE parent=?", parent)
}

// encodeEntry and decodeEntry serialize staged entries.
func encodeEntry(e library.Entry) ([]byte, error) {
	var b bytes.Buffer
	err := gob.NewEncoder(&b).Encode(e)
	return b.Bytes(), err
}

func decodeEntry(b []byte, e *library.Entry) error {
	return gob.NewDecoder(bytes.NewReader(b)).Decode(e)
}
