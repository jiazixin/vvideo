package probe

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
	"time"

	"vvideo/internal/library"
)

// testdata/grid-exif-rot.heic is a synthetic 1200×800 image encoded by macOS
// as a grid of 512×512 tiles, like an iPhone photo, from a JPEG with
// DateTimeOriginal 2023:07:08 09:10:11 +08:00 and Orientation 6.
func TestReadHEIFTiledPhoto(t *testing.T) {
	b, err := os.ReadFile("testdata/grid-exif-rot.heic")
	if err != nil {
		t.Fatal(err)
	}
	h, err := ReadHEIF(bytes.NewReader(b), int64(len(b)))
	if err != nil {
		t.Fatal(err)
	}
	want := HEIF{Width: 1200, Height: 800, Rotation: 90, CapturedRaw: "2023:07:08 09:10:11", Offset: "+08:00"}
	if h != want {
		t.Fatalf("got %+v, want %+v", h, want)
	}
	// Truncated or foreign data fails without panicking.
	for _, data := range [][]byte{b[:100], b[:len(b)/2], []byte("not heif"), {}} {
		if _, err := ReadHEIF(bytes.NewReader(data), int64(len(data))); err == nil && len(data) < 200 {
			t.Fatalf("%d bytes parsed", len(data))
		}
	}
}

func FuzzReadHEIF(f *testing.F) {
	b, err := os.ReadFile("testdata/grid-exif-rot.heic")
	if err != nil {
		f.Fatal(err)
	}
	f.Add(b[:3000])
	f.Add([]byte{0, 0, 0, 8, 'm', 'e', 't', 'a'})
	f.Fuzz(func(t *testing.T, data []byte) {
		h, err := ReadHEIF(bytes.NewReader(data), int64(len(data)))
		if err == nil && (h.Width <= 0 || h.Height <= 0 || h.Rotation%90 != 0 || h.Rotation < 0 || h.Rotation >= 360) {
			t.Fatalf("implausible result %+v", h)
		}
	})
}

// TestRunUsesHEIFItemProperties: ffprobe sees only the first 512×512 tile of a
// tiled HEIC, or nothing at all when it predates HEIF; either way Run reports
// the whole image's size, rotation and Exif capture time.
func TestRunUsesHEIFItemProperties(t *testing.T) {
	for name, script := range map[string]string{
		"first tile": "#!/bin/sh\nprintf '%s\\n' '{\"streams\":[{\"codec_type\":\"video\",\"width\":512,\"height\":512}],\"format\":{}}'\n",
		"no HEIF":    "#!/bin/sh\necho 'Invalid data found when processing input' >&2\nexit 1\n",
	} {
		t.Run(name, func(t *testing.T) {
			directory := t.TempDir()
			if err := os.WriteFile(filepath.Join(directory, "ffprobe"), []byte(script), 0700); err != nil {
				t.Fatal(err)
			}
			t.Setenv("PATH", directory+string(os.PathListSeparator)+os.Getenv("PATH"))
			f, err := os.Open("testdata/grid-exif-rot.heic")
			if err != nil {
				t.Fatal(err)
			}
			defer f.Close()
			info, err := f.Stat()
			if err != nil {
				t.Fatal(err)
			}
			r, err := Run(t.Context(), f, library.Entry{Name: "IMG_0001.HEIC", Kind: "image", Size: info.Size(), Mtime: 1})
			if err != nil {
				t.Fatal(err)
			}
			want := time.Date(2023, 7, 8, 1, 10, 11, 0, time.UTC).UnixNano()
			if r.Width != 1200 || r.Height != 800 || r.Rotation != 90 || r.EffectiveTime != want || r.CapturedSource != "exif_datetime_original" {
				t.Fatalf("got %+v", r)
			}
		})
	}
}

func TestGridFilterStitchesAndCrops(t *testing.T) {
	g := Grid{Width: 1000, Height: 600, Tiles: []Tile{{0, 0, 0}, {1, 512, 0}, {2, 0, 512}, {3, 512, 512}}}
	want := "[0:0][0:1][0:2][0:3]xstack=inputs=4:layout=0_0|512_0|0_512|512_512:fill=black,crop=1000:600:0:0"
	if got := g.Filter(); got != want {
		t.Fatalf("got %s", got)
	}
	single := Grid{Width: 500, Height: 400, Tiles: []Tile{{2, 0, 0}}}
	if got := single.Filter(); got != "[0:2]crop=500:400:0:0" {
		t.Fatalf("got %s", got)
	}
}
