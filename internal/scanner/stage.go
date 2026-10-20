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
)

type stage struct {
	db   *sql.DB
	path string
}

// newStage opens a stage under the storage directory. The namespace hashes the
// database path, so only stages left by an interrupted scan of this database
// are removed.
func newStage(ctx context.Context, storage string) (*stage, error) {
	database := filepath.Join(storage, config.DatabaseFile)
	dir := filepath.Join(config.StageDir(storage), fmt.Sprintf("%x", sha256.Sum256([]byte(database))))
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
		batch, readErr := directory.ReadDir(128)
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
	_, err = db.ExecContext(ctx, `CREATE TABLE baseline(name TEXT PRIMARY KEY,id TEXT UNIQUE,payload BLOB NOT NULL);CREATE TABLE observation(pass INTEGER,name TEXT,fingerprint TEXT,kind TEXT,size INTEGER,mtime INTEGER,directory INTEGER,PRIMARY KEY(pass,name));CREATE TABLE merged(id TEXT PRIMARY KEY,payload BLOB NOT NULL);CREATE TABLE walk(parent INTEGER,name TEXT,relative TEXT,identity TEXT,PRIMARY KEY(parent,name));`)
	if err != nil {
		db.Close()
		os.Remove(path)
		return nil, err
	}
	os.Chmod(path, 0600)
	return &stage{db: db, path: path}, nil
}
func (s *stage) Close() error { return errors.Join(s.db.Close(), os.Remove(s.path)) }
func (s *stage) Reset(ctx context.Context) error {
	_, err := s.db.ExecContext(ctx, "DELETE FROM baseline;DELETE FROM observation;DELETE FROM merged;")
	return err
}
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
func (s *stage) equal(ctx context.Context) (bool, error) {
	var n int
	err := s.db.QueryRowContext(ctx, `SELECT count(*) FROM observation a LEFT JOIN observation b ON b.pass=3-a.pass AND b.name=a.name WHERE b.name IS NULL OR a.fingerprint!=b.fingerprint OR a.kind!=b.kind OR a.size!=b.size OR a.mtime!=b.mtime OR a.directory!=b.directory`).Scan(&n)
	return n == 0, err
}
func (s *stage) put(ctx context.Context, e library.Entry) error {
	b, err := encodeEntry(e)
	if err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx, "INSERT INTO merged VALUES(?,?)", e.ID, b)
	return err
}
func (s *stage) merge(ctx context.Context, d library.Directory, ignored func(string) bool, force bool, resolve func(string) (string, error)) (int64, error) {
	var changed int64
	after := ""
	for {
		rows, err := s.db.QueryContext(ctx, "SELECT id,payload FROM baseline WHERE id>? ORDER BY id LIMIT 128", after)
		if err != nil {
			return 0, err
		}
		batch := []library.Entry{}
		for rows.Next() {
			var id string
			var b []byte
			if err = rows.Scan(&id, &b); err != nil {
				rows.Close()
				return 0, err
			}
			var e library.Entry
			if err = decodeEntry(b, &e); err != nil {
				rows.Close()
				return 0, err
			}
			batch = append(batch, e)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return 0, err
		}
		if len(batch) == 0 {
			break
		}
		for _, e := range batch {
			after = e.ID
			original := e
			var a Attributes
			a.Name = e.Name
			err := s.db.QueryRowContext(ctx, "SELECT fingerprint,kind,size,mtime FROM observation WHERE pass=2 AND name=? AND directory=0", e.Name).Scan(&a.Fingerprint, &a.Kind, &a.Size, &a.Mtime)
			if errors.Is(err, sql.ErrNoRows) {
				if ignored(e.Name) {
					e.State = "ignored"
					e.Present = false
					e.MissingStreak = 0
				} else {
					e.Present = false
				}
			} else if err != nil {
				return 0, err
			} else {
				e.Present = true
				e.State = "visible"
				e.MissingStreak = 0
				e.ObservedRound = 0
				if e.Fingerprint != a.Fingerprint || force {
					e = changedEntry(d, e.ID, a)
					changed++
				}
			}
			if e.State != original.State || e.Present != original.Present {
				changed++
			}
			if err = s.put(ctx, e); err != nil {
				return 0, err
			}
		}
	}
	after = ""
	for {
		rows, err := s.db.QueryContext(ctx, `SELECT o.name,o.fingerprint,o.kind,o.size,o.mtime FROM observation o LEFT JOIN baseline b ON b.name=o.name WHERE o.pass=2 AND o.directory=0 AND b.id IS NULL AND o.name>? ORDER BY o.name LIMIT 128`, after)
		if err != nil {
			return 0, err
		}
		batch := []Attributes{}
		for rows.Next() {
			var a Attributes
			if err = rows.Scan(&a.Name, &a.Fingerprint, &a.Kind, &a.Size, &a.Mtime); err != nil {
				rows.Close()
				return 0, err
			}
			batch = append(batch, a)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return 0, err
		}
		if len(batch) == 0 {
			break
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
			changed++
		}
	}
	return changed, nil
}
func changedEntry(d library.Directory, id string, a Attributes) library.Entry {
	return library.Entry{ID: id, Version: uuid.New().String(), DirectoryID: d.ID, LibraryID: d.LibraryID, Name: a.Name, Kind: a.Kind, Fingerprint: a.Fingerprint, Size: a.Size, Mtime: a.Mtime, EffectiveTime: a.Mtime, State: "visible", Present: true, NewVersion: true}
}
func (s *stage) merged(ctx context.Context, after string) ([]library.Entry, error) {
	rows, err := s.db.QueryContext(ctx, "SELECT payload FROM merged WHERE id>? ORDER BY id LIMIT 128", after)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []library.Entry{}
	for rows.Next() {
		var b []byte
		if err = rows.Scan(&b); err != nil {
			return nil, err
		}
		var e library.Entry
		if err = decodeEntry(b, &e); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

func encodeEntry(e library.Entry) ([]byte, error) {
	var b bytes.Buffer
	err := gob.NewEncoder(&b).Encode(e)
	return b.Bytes(), err
}
func decodeEntry(b []byte, e *library.Entry) error {
	return gob.NewDecoder(bytes.NewReader(b)).Decode(e)
}
