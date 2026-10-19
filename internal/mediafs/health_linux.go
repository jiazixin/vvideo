//go:build linux

package mediafs

import (
	"errors"
	"golang.org/x/sys/unix"
	"os"
)

func mountIdentity(path string, file *os.File) (string, error) {
	f, err := os.Open("/proc/self/mountinfo")
	if err != nil {
		return "", err
	}
	defer f.Close()
	var observed unix.Statx_t
	var mountID uint64
	if unix.Statx(int(file.Fd()), "", unix.AT_EMPTY_PATH|unix.AT_SYMLINK_NOFOLLOW, unix.STATX_MNT_ID, &observed) == nil && observed.Mask&unix.STATX_MNT_ID != 0 {
		mountID = observed.Mnt_id
	}
	record, err := selectMount(f, path, mountID)
	if err != nil {
		return "", err
	}
	var st unix.Statfs_t
	if err = unix.Fstatfs(int(file.Fd()), &st); err != nil || st.Blocks == 0 || st.Fsid.Val == [2]int32{} {
		return "", errors.New("filesystem health cannot be established")
	}
	return mountIdentityFor(record, int64(st.Type), st.Fsid.Val), nil
}
