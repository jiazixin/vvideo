package mediafs

import "golang.org/x/sys/unix"

// Space reports the total and available bytes of the filesystem holding path.
func Space(path string) (total, free int64, err error) {
	var s unix.Statfs_t
	if err = unix.Statfs(path, &s); err != nil {
		return 0, 0, err
	}
	return int64(s.Blocks) * int64(s.Bsize), int64(s.Bavail) * int64(s.Bsize), nil
}
