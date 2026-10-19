//go:build linux

package mediafs

import (
	"os"
	"path/filepath"
	"testing"
)

func TestMountProbeUsesPinnedDescriptor(t *testing.T) {
	path := filepath.Join(t.TempDir(), "root")
	if err := os.Mkdir(path, 0700); err != nil {
		t.Fatal(err)
	}
	file, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	before, err := mountIdentity(path, file)
	if err != nil {
		t.Fatal(err)
	}
	if err = os.Rename(path, path+".old"); err != nil {
		t.Fatal(err)
	}
	// The named root is gone. Health observation must still refer to the pinned
	// descriptor; publication separately validates the current named root.
	pinned, err := mountIdentity(path, file)
	if err != nil || pinned != before {
		t.Fatal("health lookup followed the disappeared pathname", pinned, err)
	}
}

func TestConfiguredRootIdentity(t *testing.T) {
	path := os.Getenv("VVIDEO_IDENTITY_TEST_ROOT")
	if path == "" {
		t.Skip("explicit isolated root comparison required")
	}
	root, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer root.Close()
	expected := os.Getenv("VVIDEO_EXPECTED_ROOT_IDENTITY")
	if expected != "" && root.Identity != expected {
		t.Fatal("same physical root changed identity across service namespace", root.Identity)
	}
	t.Log("root_identity", root.Identity)
}
