package scanner

import (
	"database/sql"
	"testing"
	"vvideo/internal/mediafs"
)

func TestLegacyIdentityUpgradeRequiresExactCurrentProof(t *testing.T) {
	s, path := fixture(t)
	create(t, path, "kept.mp4")
	if err := s.Run(t.Context(), false); err != nil {
		t.Fatal(err)
	}
	libs, err := s.Index.Libraries(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	before := libs[0]
	root, err := mediafs.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer root.Close()
	if err = s.Index.Store.Write(t.Context(), 0, func(tx *sql.Tx) error {
		_, e := tx.ExecContext(t.Context(), "UPDATE libraries SET identity=? WHERE id=?", root.LegacyIdentity, before.ID)
		return e
	}); err != nil {
		t.Fatal(err)
	}
	if err = s.Run(t.Context(), false); err != nil {
		t.Fatal(err)
	}
	libs, err = s.Index.Libraries(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if libs[0].ID != before.ID || libs[0].Identity != root.Identity || count(t, s) != 1 {
		t.Fatal("legacy upgrade changed library identity or content", libs)
	}
	if err = s.Index.Store.Write(t.Context(), 0, func(tx *sql.Tx) error {
		_, e := tx.ExecContext(t.Context(), "UPDATE libraries SET identity=? WHERE id=?", root.LegacyIdentity+"different", before.ID)
		return e
	}); err != nil {
		t.Fatal(err)
	}
	if err = s.Run(t.Context(), true); err == nil {
		t.Fatal("force scan adopted unverified changed filesystem")
	}
	if count(t, s) != 1 {
		t.Fatal("failed identity check deleted prior media")
	}
}
