//go:build linux

package mediafs

import "syscall"

func changeTime(stat *syscall.Stat_t) syscall.Timespec { return stat.Ctim }
