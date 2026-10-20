package library

import (
	"context"
	"errors"
	"os"
	"path/filepath"

	"vvideo/internal/mediafs"
)

// OpenMedia opens the published version e through its root descriptor without
// following symlinks. It fails with ErrConflict if the file's fingerprint no
// longer matches. The returned validate function re-checks, after the caller
// has used the file, that the same file is still at that path, unchanged.
func (i *Index) OpenMedia(ctx context.Context, e Entry) (*os.File, func() error, error) {
	d, err := i.Directory(ctx, e.DirectoryID)
	if err != nil {
		return nil, nil, err
	}
	var rootPath, identity string
	if err = i.Store.Reader.QueryRowContext(ctx, "SELECT root,identity FROM libraries WHERE id=? AND active=1", e.LibraryID).Scan(&rootPath, &identity); err != nil {
		return nil, nil, err
	}
	r, err := mediafs.Open(rootPath)
	if err != nil {
		return nil, nil, err
	}
	if !r.Matches(identity) {
		r.Close()
		return nil, nil, ErrUnavailable
	}
	relative := filepath.Join(d.Relative, e.Name)
	f, err := r.OpenFile(relative)
	if err != nil {
		r.Close()
		return nil, nil, err
	}
	r.Close()
	info, err := f.Stat()
	if err != nil || !info.Mode().IsRegular() || mediafs.Fingerprint(info) != e.Fingerprint {
		f.Close()
		return nil, nil, ErrConflict
	}
	validate := func() error {
		now, err := f.Stat()
		if err != nil {
			return err
		}
		if mediafs.Fingerprint(now) != e.Fingerprint {
			return ErrConflict
		}
		r, err := mediafs.Open(rootPath)
		if err != nil {
			return err
		}
		defer r.Close()
		if !r.Matches(identity) {
			return ErrUnavailable
		}
		other, err := r.OpenFile(relative)
		if err != nil {
			return err
		}
		defer other.Close()
		n, err := other.Stat()
		if err != nil {
			return err
		}
		if !os.SameFile(info, n) || mediafs.Fingerprint(n) != e.Fingerprint {
			return errors.New("source file changed")
		}
		return nil
	}
	return f, validate, nil
}
