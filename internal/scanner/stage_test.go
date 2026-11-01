package scanner

import (
	"errors"
	"io/fs"
	"os"
	"testing"
)

func TestStagePrivateFileAndCleanup(t *testing.T) {
	stage, err := newStage(t.Context(), t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(stage.path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Errorf("stage permissions %o, want 600", info.Mode().Perm())
	}
	if err := stage.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(stage.path); !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("stage remains after cleanup: %v", err)
	}
}
