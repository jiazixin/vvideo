package probe

import (
	"encoding/binary"
	"os"
	"path/filepath"
	"testing"
	"time"
	"vvideo/internal/library"
)

func exifTimeFixture(raw, offset string) []byte {
	values := []struct {
		tag  uint16
		text string
	}{{0x9003, raw + "\x00"}}
	if offset != "" {
		values = append(values, struct {
			tag  uint16
			text string
		}{0x9011, offset + "\x00"})
	}
	const subIFD = 26
	start := subIFD + 2 + 12*len(values) + 4
	size := start
	for _, v := range values {
		size += len(v.text)
	}
	b := make([]byte, size)
	order := binary.LittleEndian
	copy(b, "II")
	order.PutUint16(b[2:], 42)
	order.PutUint32(b[4:], 8)
	order.PutUint16(b[8:], 1)
	order.PutUint16(b[10:], 0x8769)
	order.PutUint16(b[12:], 4)
	order.PutUint32(b[14:], 1)
	order.PutUint32(b[18:], subIFD)
	order.PutUint16(b[subIFD:], uint16(len(values)))
	for index, v := range values {
		entry := b[subIFD+2+12*index : subIFD+14+12*index]
		order.PutUint16(entry, v.tag)
		order.PutUint16(entry[2:], 2)
		order.PutUint32(entry[4:], uint32(len(v.text)))
		if len(v.text) <= 4 {
			copy(entry[8:], v.text)
		} else {
			order.PutUint32(entry[8:], uint32(start))
			copy(b[start:], v.text)
			start += len(v.text)
		}
	}
	jpeg := []byte{0xff, 0xd8, 0xff, 0xe1, 0, 0}
	binary.BigEndian.PutUint16(jpeg[4:], uint16(len(b)+8))
	jpeg = append(jpeg, "Exif\x00\x00"...)
	jpeg = append(jpeg, b...)
	return append(jpeg, 0xff, 0xd9)
}

func TestOriginalExifUnknownTimezoneOverridesContainerTime(t *testing.T) {
	directory := t.TempDir()
	// Isolate external-tool parsing while exercising the actual Run path and
	// EXIF file reader. This fixture is metadata, not a playable JPEG sample.
	script := "#!/bin/sh\nprintf '%s\\n' '{\"streams\":[{\"codec_type\":\"video\",\"width\":80,\"height\":40}],\"format\":{\"tags\":{\"creation_time\":\"2025-06-01T00:00:00Z\"}}}'\n"
	if err := os.WriteFile(filepath.Join(directory, "ffprobe"), []byte(script), 0700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", directory+string(os.PathListSeparator)+os.Getenv("PATH"))
	mtime := time.Date(2024, 1, 1, 0, 0, 0, 0, time.UTC).UnixNano()
	for _, tc := range []struct{ name, raw, offset string }{
		{"missing", "2020:02:03 04:05:06", ""},
		{"invalid-offset", "2020:02:03 04:05:06", "bad"},
		{"invalid-date", "2020:13:03 04:05:06", "+08:00"},
		{"out-of-range", "1960:02:03 04:05:06", "+08:00"},
		{"known", "2020:02:03 04:05:06", "+08:00"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "metadata.jpg")
			if err := os.WriteFile(path, exifTimeFixture(tc.raw, tc.offset), 0600); err != nil {
				t.Fatal(err)
			}
			f, err := os.Open(path)
			if err != nil {
				t.Fatal(err)
			}
			defer f.Close()
			result, err := Run(t.Context(), f, library.Entry{Kind: "image", Mtime: mtime})
			if err != nil {
				t.Fatal(err)
			}
			want, source := mtime, "mtime"
			if tc.name == "known" {
				parsed, err := time.Parse("2006:01:02 15:04:05-07:00", tc.raw+tc.offset)
				if err != nil {
					t.Fatal(err)
				}
				want, source = parsed.UnixNano(), "exif_datetime_original"
			}
			if result.CapturedRaw != tc.raw || result.CapturedSource != source || result.EffectiveTime != want {
				t.Fatalf("time key/source mismatch: got %+v want time=%d source=%s raw=%s", result, want, source, tc.raw)
			}
		})
	}
}
