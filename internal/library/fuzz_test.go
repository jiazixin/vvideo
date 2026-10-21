package library

import (
	"errors"
	"testing"
	"unicode/utf8"
)

// FuzzDecodeCursor: any client-supplied cursor either fails as ErrInvalid or
// decodes to a value that survives another encode/decode unchanged.
func FuzzDecodeCursor(f *testing.F) {
	f.Add(EncodeCursor(Cursor{DirectoryID: 1, Revision: 2, Sort: "name", Direction: "asc", Key: "a.mp4", ID: "x"}))
	f.Add("")
	f.Add("e30")
	f.Add("!!!")
	f.Fuzz(func(t *testing.T, s string) {
		c, err := DecodeCursor(s)
		if err != nil {
			if !errors.Is(err, ErrInvalid) {
				t.Fatalf("error %v is not ErrInvalid", err)
			}
			return
		}
		again, err := DecodeCursor(EncodeCursor(c))
		if err != nil || again != c {
			t.Fatalf("round trip changed %+v to %+v (%v)", c, again, err)
		}
	})
}

// FuzzCursorRoundTrip: every cursor the server can build, including one whose
// key is a file name that is not valid UTF-8, decodes back to itself.
func FuzzCursorRoundTrip(f *testing.F) {
	f.Add(int64(1), int64(0), "name", "asc", "", "clip.mp4", "id")
	f.Add(int64(7), int64(3), "name", "desc", "video", "\xff\xfe\xb2\xe2.mp4", "id")
	f.Fuzz(func(t *testing.T, directory, revision int64, sort, direction, kind, key, id string) {
		// Only the key comes from the file system; the rest are validated
		// options and IDs, which are always UTF-8.
		if !utf8.ValidString(sort) || !utf8.ValidString(direction) || !utf8.ValidString(kind) || !utf8.ValidString(id) {
			return
		}
		c := Cursor{DirectoryID: directory, Revision: revision, Sort: sort, Direction: direction, Kind: kind, Key: key, ID: id}
		got, err := DecodeCursor(EncodeCursor(c))
		if err != nil {
			if len(EncodeCursor(c)) > maxCursorLength {
				return
			}
			t.Fatalf("cursor for %q does not decode: %v", key, err)
		}
		if got != c {
			t.Fatalf("round trip changed %+v to %+v", c, got)
		}
	})
}
