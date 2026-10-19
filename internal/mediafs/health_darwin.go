//go:build darwin

package mediafs

import "os"

// Darwin is a development adapter, not NAS mount-health verification.
func mountIdentity(path string, file *os.File) (string, error) {
	return "darwin-test-adapter", nil
}
