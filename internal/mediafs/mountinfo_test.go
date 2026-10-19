package mediafs

import (
	"strings"
	"testing"
)

func TestMountIdentitySurvivesEphemeralNumbersAndOptions(t *testing.T) {
	first := "36 35 0:41 /library /mnt/media rw,noatime shared:1 - nfs4 nas:/share rw,vers=4.2\n"
	remounted := "99 20 0:77 /library /mnt/media ro master:42 future:1 - nfs4 nas:/share ro,vers=4.2\n"
	a, err := selectMount(strings.NewReader(first), "/mnt/media/videos", 36)
	if err != nil {
		t.Fatal(err)
	}
	b, err := selectMount(strings.NewReader(remounted), "/mnt/media/videos", 99)
	if err != nil {
		t.Fatal(err)
	}
	stable := mountIdentityFor(a, 0x6969, [2]int32{12, 34})
	if next := mountIdentityFor(b, 0x6969, [2]int32{12, 34}); stable != next {
		t.Fatal("ephemeral mount fields polluted persistent identity")
	}
	for _, line := range []string{
		strings.Replace(remounted, "nas:/share", "other:/share", 1),
		strings.Replace(remounted, "nfs4", "cifs", 1),
	} {
		record, e := selectMount(strings.NewReader(line), "/mnt/media/videos", 99)
		if e != nil {
			t.Fatal(e)
		}
		changed := mountIdentityFor(record, 0x6969, [2]int32{12, 34})
		if changed == stable {
			t.Fatal("changed filesystem source accepted")
		}
	}
	// Read-only bind namespaces may change mount layout; Root checks the
	// actual opened directory inode, rather than persisting that layout.
	bound := b
	bound.root = "/library/subtree"
	bound.point = "/home"
	same := mountIdentityFor(bound, 0x6969, [2]int32{12, 34})
	if same != stable {
		t.Fatal("namespace layout changed physical volume identity")
	}
	changed := mountIdentityFor(b, 0x6969, [2]int32{12, 35})
	if changed == stable {
		t.Fatal("changed filesystem ID accepted")
	}
}
func TestMountSelectionUsesCurrentKernelIDAndBoundaries(t *testing.T) {
	mounts := "1 0 8:1 / / rw - ext4 /dev/root rw\n2 1 0:1 / /mnt/media rw - nfs4 old:/share rw\n3 2 0:2 /new /mnt/media rw - nfs4 new:/share rw\n4 1 0:3 / /mnt/media-extra rw - nfs4 unrelated:/share rw\n"
	actual, err := selectMount(strings.NewReader(mounts), "/mnt/media/file.mp4", 3)
	if err != nil || actual.source != "new:/share" {
		t.Fatal(actual, err)
	}
	if _, err = selectMount(strings.NewReader(mounts), "/mnt/media/file.mp4", 0); err == nil {
		t.Fatal("ambiguous stacked mounts accepted without kernel ID")
	}
	outside, err := selectMount(strings.NewReader(mounts), "/mnt/mediator/file.mp4", 0)
	if err != nil || outside.source != "/dev/root" {
		t.Fatal("path prefix escaped mount component", outside, err)
	}
	escaped, err := selectMount(strings.NewReader("9 1 0:1 /a\\040b /mnt/a\\040b rw - nfs4 nas:/a\\040b rw\n"), "/mnt/a b/video.mp4", 9)
	if err != nil || escaped.root != "/a b" || escaped.source != "nas:/a b" {
		t.Fatal(escaped, err)
	}
}
