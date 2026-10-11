//go:build darwin

package mediafs

import "syscall"

func changeTime(stat *syscall.Stat_t) syscall.Timespec { return stat.Ctimespec }
