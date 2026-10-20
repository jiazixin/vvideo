// Package mediafs supplies descriptor-relative, non-following, read-only access.
// Linux is the deployment target; Darwin uses the same openat test adapter.
package mediafs

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"syscall"

	"golang.org/x/sys/unix"
)

type Root struct {
	Path, Identity string
	file           *os.File
}

func Fingerprint(info fs.FileInfo) string {
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok {
		return fmt.Sprintf("%d:%d", info.Size(), info.ModTime().UnixNano())
	}
	// Metadata change time catches same-size writes followed by mtime restore.
	// It is a version guard, never a capture-time sorting source. The device
	// number is left out: network mounts (CIFS, NFS) get an anonymous one at
	// mount time, so a remount would otherwise make every file look changed.
	changed := changeTime(stat)
	return fmt.Sprintf("f3:%d:%d:%d:%d:%d", stat.Ino, info.Size(), info.ModTime().UnixNano(), changed.Sec, changed.Nsec)
}
func Open(path string) (*Root, error) {
	fd, err := unix.Open(path, unix.O_RDONLY|unix.O_DIRECTORY|unix.O_CLOEXEC|unix.O_NOFOLLOW, 0)
	if err != nil {
		return nil, err
	}
	f := os.NewFile(uintptr(fd), path)
	i, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, err
	}
	mount, err := mountIdentity(path, f)
	if err != nil {
		f.Close()
		return nil, err
	}
	inode := i.Name()
	if stat, ok := i.Sys().(*syscall.Stat_t); ok {
		inode = fmt.Sprint(stat.Ino)
	}
	return &Root{Path: path, Identity: "v2:" + inode + ":" + mount, file: f}, nil
}

// Matches reports whether a persisted identity names this root.
func (r *Root) Matches(identity string) bool {
	return identity == r.Identity
}
func (r *Root) Close() error { return r.file.Close() }
func (r *Root) Validate() error {
	other, err := Open(r.Path)
	if err != nil {
		return err
	}
	defer other.Close()
	if other.Identity != r.Identity {
		return errors.New("root filesystem identity changed")
	}
	return nil
}

// OpenFile opens each component relative to an already opened parent. No media
// symlink is followed even when its target would still be inside the root.
func (r *Root) OpenFile(relative string) (*os.File, error) {
	if !filepath.IsLocal(relative) {
		return nil, errors.New("invalid relative media path")
	}
	components := strings.Split(filepath.Clean(relative), string(filepath.Separator))
	fd, err := unix.Dup(int(r.file.Fd()))
	if err != nil {
		return nil, err
	}
	for i, c := range components {
		flags := unix.O_RDONLY | unix.O_NOFOLLOW | unix.O_CLOEXEC
		if i < len(components)-1 || relative == "." {
			flags |= unix.O_DIRECTORY
		}
		next, err := unix.Openat(fd, c, flags, 0)
		unix.Close(fd)
		if err != nil {
			return nil, err
		}
		fd = next
	}
	// Name the file by its root-relative path: it appears in error messages,
	// which must not carry full media paths into logs.
	return os.NewFile(uintptr(fd), relative), nil
}
func (r *Root) ValidateDirectory(relative string, original fs.FileInfo) error {
	if err := r.Validate(); err != nil {
		return err
	}
	f, err := r.OpenFile(relative)
	if err != nil {
		return err
	}
	defer f.Close()
	now, err := f.Stat()
	if err != nil {
		return err
	}
	if !os.SameFile(original, now) {
		return errors.New("directory path identity changed")
	}
	return nil
}

// StatAt uses the pinned directory descriptor and performs exactly one attribute
// operation per selected entry. The opened directory must outlive its workers.
func StatAt(directory *os.File, name string) (fs.FileInfo, error) {
	fd, err := unix.Openat(int(directory.Fd()), name, unix.O_RDONLY|unix.O_NOFOLLOW|unix.O_NONBLOCK|unix.O_CLOEXEC, 0)
	if err != nil {
		return nil, err
	}
	f := os.NewFile(uintptr(fd), name)
	defer f.Close()
	return f.Stat()
}
