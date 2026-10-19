package mediafs

import (
	"bufio"
	"crypto/sha256"
	"errors"
	"fmt"
	"io"
	"path/filepath"
	"strconv"
	"strings"
)

type mountRecord struct{ root, point, kind, source string }

// Mount numbers and options are observations of this mount namespace, not
// persistent volume identity. Bind-root/point layout can also change inside
// a read-only service namespace; the opened root inode identifies the directory.
func selectMount(reader io.Reader, path string, mountID uint64) (mountRecord, error) {
	scan := bufio.NewScanner(reader)
	scan.Buffer(make([]byte, 4096), 1<<20)
	var best mountRecord
	length := -1
	ambiguous := false
	for scan.Scan() {
		// Field positions and the variable optional-field separator require indexing.
		fields := strings.Fields(scan.Text())
		if len(fields) < 10 {
			continue
		}
		point := unescape(fields[4])
		rel, err := filepath.Rel(point, path)
		if err != nil || rel == ".." || strings.HasPrefix(rel, "../") {
			continue
		}
		separator := -1
		for index := 6; index < len(fields); index++ {
			if fields[index] == "-" {
				separator = index
				break
			}
		}
		if separator < 0 || separator+3 >= len(fields) {
			continue
		}
		id, err := strconv.ParseUint(fields[0], 10, 64)
		if err != nil {
			continue
		}
		if mountID != 0 && id != mountID {
			continue
		}
		candidate := mountRecord{unescape(fields[3]), point, fields[separator+1], unescape(fields[separator+2])}
		if len(point) > length {
			best = candidate
			length = len(point)
			ambiguous = false
		} else if len(point) == length && mountID == 0 {
			ambiguous = true
		}
	}
	if err := scan.Err(); err != nil {
		return mountRecord{}, err
	}
	if length < 0 || ambiguous {
		return mountRecord{}, errors.New("cannot establish unambiguous mount identity")
	}
	return best, nil
}
func mountIdentityFor(record mountRecord, filesystemType int64, fsid [2]int32) string {
	key := fmt.Sprintf("%s\x00%s\x00%d\x00%d,%d", record.kind, record.source, filesystemType, fsid[0], fsid[1])
	sum := sha256.Sum256([]byte(key))
	return fmt.Sprintf("%x", sum[:12])
}
func unescape(s string) string {
	for _, v := range []string{"040", "011", "012", "134"} {
		n, _ := strconv.ParseInt(v, 8, 8)
		s = strings.ReplaceAll(s, "\\"+v, string(rune(n)))
	}
	return s
}
