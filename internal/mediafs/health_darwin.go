//go:build darwin

package mediafs

import "os"

// mountIdentity is a development adapter on Darwin; it does not verify NAS
// mount health.
func mountIdentity(_ string, _ *os.File) (string, error) {
	return "darwin-test-adapter", nil
}
