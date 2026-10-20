//go:build darwin

package mediafs

import "os"

// mountIdentity is a development adapter on Darwin; it does not verify NAS
// mount health.
func mountIdentity(path string, file *os.File) (string, error) {
	return "darwin-test-adapter", nil
}
