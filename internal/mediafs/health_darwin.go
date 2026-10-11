//go:build darwin

package mediafs

import "os"

// Darwin is a development adapter, not NAS mount-health verification.
func mountIdentities(path string, file *os.File) (stable, legacy string, err error) {
	return "darwin-test-adapter", "darwin-test-adapter", nil
}
