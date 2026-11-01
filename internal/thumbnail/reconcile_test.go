package thumbnail

import (
	"errors"
	"io/fs"
	"testing"
)

type failedTempEntry struct {
	err error
}

func (failedTempEntry) Name() string                 { return ".frame-fixture.webp" }
func (failedTempEntry) IsDir() bool                  { return false }
func (failedTempEntry) Type() fs.FileMode            { return 0 }
func (e failedTempEntry) Info() (fs.FileInfo, error) { return nil, e.err }

func TestTempReconcileReportsReadFailure(t *testing.T) {
	engine := &Engine{}
	if err := engine.removeIfOrphan(t.Context(), failedTempEntry{fs.ErrPermission}); !errors.Is(err, fs.ErrPermission) {
		t.Fatalf("permission error was lost: %v", err)
	}
	if err := engine.removeIfOrphan(t.Context(), failedTempEntry{fs.ErrNotExist}); err != nil {
		t.Fatalf("already-removed temporary file: %v", err)
	}
}
