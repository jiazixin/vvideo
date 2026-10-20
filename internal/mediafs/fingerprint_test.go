package mediafs

import (
	"io/fs"
	"syscall"
	"testing"
	"time"
)

type statInfo struct {
	fs.FileInfo
	stat *syscall.Stat_t
}

func (i statInfo) Size() int64        { return 42 }
func (i statInfo) ModTime() time.Time { return time.Unix(1_700_000_000, 5) }
func (i statInfo) Sys() any           { return i.stat }

// TestFingerprintIgnoresDevice: a remount that only changes the device number
// leaves the fingerprint alone; a different inode still changes it.
func TestFingerprintIgnoresDevice(t *testing.T) {
	before := Fingerprint(statInfo{stat: &syscall.Stat_t{Dev: 51, Ino: 7}})
	if after := Fingerprint(statInfo{stat: &syscall.Stat_t{Dev: 52, Ino: 7}}); after != before {
		t.Fatalf("device change altered fingerprint: %s -> %s", before, after)
	}
	if other := Fingerprint(statInfo{stat: &syscall.Stat_t{Dev: 51, Ino: 8}}); other == before {
		t.Fatal("inode change not detected")
	}
}
