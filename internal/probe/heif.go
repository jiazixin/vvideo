package probe

import (
	"context"
	"encoding/binary"
	"encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"
)

const (
	// maxMetaSize bounds the HEIF meta box read into memory.
	maxMetaSize = 4 << 20
	// maxExifSize bounds the Exif item read from a HEIF file.
	maxExifSize = 256 << 10
	// maxTopBoxes bounds the top-level boxes walked before meta.
	maxTopBoxes = 64
)

// HEIF is what a HEIF/AVIF file's metadata says about its primary image:
// the stored size (before rotation), the clockwise rotation from its irot
// property, and the capture time from its Exif item, if any.
type HEIF struct {
	Width, Height, Rotation int
	CapturedRaw, Offset     string
}

// ReadHEIF reads the primary image properties of a HEIF/AVIF file. ffprobe
// reports only the first tile of a tiled image (such as an iPhone photo, a
// grid of 512×512 tiles) and does not read its Exif item, so the size,
// rotation and capture time come from the file's own item properties.
func ReadHEIF(r io.ReaderAt, size int64) (HEIF, error) {
	var h HEIF
	meta, err := findMeta(r, size)
	if err != nil {
		return h, err
	}
	var primary uint32
	type property struct {
		kind string
		body []byte
	}
	var properties []property // ipco children, 1-based in ipma
	associations := map[uint32][]int{}
	exif := map[uint32]bool{}
	var iloc []byte
	for kind, body := range children(meta[4:]) { // meta is a FullBox
		switch kind {
		case "pitm":
			if len(body) >= 6 && body[0] == 0 {
				primary = uint32(binary.BigEndian.Uint16(body[4:]))
			} else if len(body) >= 8 {
				primary = binary.BigEndian.Uint32(body[4:])
			}
		case "iinf":
			parseIinf(body, exif)
		case "iprp":
			for kind, body := range children(body) {
				switch kind {
				case "ipco":
					for kind, body := range children(body) {
						properties = append(properties, property{kind, body})
					}
				case "ipma":
					parseIpma(body, associations)
				}
			}
		case "iloc":
			iloc = body
		}
	}
	if primary == 0 {
		return h, errors.New("heif: no primary item")
	}
	for _, index := range associations[primary] {
		if index < 1 || index > len(properties) {
			continue
		}
		switch p := properties[index-1]; p.kind {
		case "ispe": // FullBox header, then width and height
			if len(p.body) >= 12 {
				h.Width = int(binary.BigEndian.Uint32(p.body[4:]))
				h.Height = int(binary.BigEndian.Uint32(p.body[8:]))
			}
		case "irot":
			if len(p.body) >= 1 {
				// irot turns anticlockwise in quarter turns.
				h.Rotation = (4 - int(p.body[0]&3)) % 4 * 90
			}
		}
	}
	if h.Width <= 0 || h.Height <= 0 {
		return h, errors.New("heif: primary item has no size")
	}
	for id := range exif {
		data := readItem(r, size, iloc, id)
		// Exif item data starts with the offset of the TIFF header.
		if len(data) < 4 {
			continue
		}
		start := 4 + int64(binary.BigEndian.Uint32(data))
		if start < int64(len(data)) {
			h.CapturedRaw, h.Offset, _ = tiff(data[start:])
		}
		break
	}
	return h, nil
}

// findMeta returns the body of the top-level meta box.
func findMeta(r io.ReaderAt, size int64) ([]byte, error) {
	var head [16]byte
	for offset, n := int64(0), 0; offset+8 <= size && n < maxTopBoxes; n++ {
		if _, err := r.ReadAt(head[:8], offset); err != nil {
			return nil, err
		}
		length, headLength := int64(binary.BigEndian.Uint32(head[:4])), int64(8)
		if length == 1 {
			if _, err := r.ReadAt(head[8:16], offset+8); err != nil {
				return nil, err
			}
			length, headLength = int64(binary.BigEndian.Uint64(head[8:16])), 16
		} else if length == 0 {
			length = size - offset
		}
		if length < headLength || length > size-offset {
			return nil, errors.New("heif: invalid box")
		}
		if string(head[4:8]) == "meta" {
			if length-headLength > maxMetaSize || length-headLength < 4 {
				return nil, errors.New("heif: meta box size out of range")
			}
			body := make([]byte, length-headLength)
			_, err := r.ReadAt(body, offset+headLength)
			return body, err
		}
		offset += length
	}
	return nil, errors.New("heif: no meta box")
}

// children yields each child box of b as its type and body. Iteration stops
// at the first malformed box.
func children(b []byte) func(yield func(string, []byte) bool) {
	return func(yield func(string, []byte) bool) {
		for len(b) >= 8 {
			length := int(binary.BigEndian.Uint32(b))
			if length < 8 || length > len(b) {
				return
			}
			if !yield(string(b[4:8]), b[8:length]) {
				return
			}
			b = b[length:]
		}
	}
}

// parseIinf records the IDs of Exif items in an iinf body.
func parseIinf(b []byte, exif map[uint32]bool) {
	if len(b) < 4 {
		return
	}
	skip := 6 // FullBox header and a 16-bit entry count
	if b[0] != 0 {
		skip = 8
	}
	if len(b) < skip {
		return
	}
	for kind, infe := range children(b[skip:]) {
		if kind != "infe" || len(infe) < 4 {
			continue
		}
		var id uint32
		var itemType []byte
		switch infe[0] {
		case 2:
			if len(infe) >= 12 {
				id, itemType = uint32(binary.BigEndian.Uint16(infe[4:])), infe[8:12]
			}
		case 3:
			if len(infe) >= 14 {
				id, itemType = binary.BigEndian.Uint32(infe[4:]), infe[10:14]
			}
		}
		if string(itemType) == "Exif" {
			exif[id] = true
		}
	}
}

// parseIpma maps item IDs to their 1-based ipco property indexes.
func parseIpma(b []byte, associations map[uint32][]int) {
	if len(b) < 8 {
		return
	}
	version, wide := b[0], b[3]&1 == 1
	count := binary.BigEndian.Uint32(b[4:])
	p := 8
	for range min(count, 1<<16) {
		var id uint32
		if version < 1 {
			if p+2 > len(b) {
				return
			}
			id, p = uint32(binary.BigEndian.Uint16(b[p:])), p+2
		} else {
			if p+4 > len(b) {
				return
			}
			id, p = binary.BigEndian.Uint32(b[p:]), p+4
		}
		if p >= len(b) {
			return
		}
		n := int(b[p])
		p++
		for range n {
			if wide {
				if p+2 > len(b) {
					return
				}
				associations[id] = append(associations[id], int(binary.BigEndian.Uint16(b[p:])&0x7fff))
				p += 2
			} else {
				if p >= len(b) {
					return
				}
				associations[id] = append(associations[id], int(b[p]&0x7f))
				p++
			}
		}
	}
}

// readItem returns the data of item id when iloc places it in one extent of
// the file itself, bounded by maxExifSize; otherwise nil.
func readItem(r io.ReaderAt, size int64, iloc []byte, id uint32) []byte {
	if len(iloc) < 4 {
		return nil
	}
	version := iloc[0]
	f := fields{b: iloc, p: 4, ok: true} // after the FullBox header
	sizes := f.uint(1)
	offsetSize, lengthSize := int(sizes>>4), int(sizes&15)
	sizes = f.uint(1)
	baseSize, indexSize := int(sizes>>4), 0
	if version == 1 || version == 2 {
		indexSize = int(sizes & 15)
	}
	idSize := 2 // item_count and item_ID widths
	if version >= 2 {
		idSize = 4
	}
	count := f.uint(idSize)
	for range min(count, 1<<16) {
		item := f.uint(idSize)
		var method uint64
		if version == 1 || version == 2 {
			method = f.uint(2) & 15 // construction_method
		}
		f.uint(2) // data_reference_index
		base := f.uint(baseSize)
		extents := f.uint(2)
		var offset, length uint64
		for range extents {
			f.uint(indexSize)
			offset, length = f.uint(offsetSize), f.uint(lengthSize)
		}
		if !f.ok {
			return nil
		}
		if item == uint64(id) {
			if method != 0 || extents != 1 {
				return nil // in idat or split: not worth following for Exif
			}
			return readExtent(r, size, base+offset, length)
		}
	}
	return nil
}

// readExtent reads length bytes at start, which must lie inside the file and
// not exceed maxExifSize.
func readExtent(r io.ReaderAt, size int64, start, length uint64) []byte {
	if length == 0 || length > maxExifSize || start >= uint64(size) || length > uint64(size)-start {
		return nil
	}
	data := make([]byte, length)
	if _, err := r.ReadAt(data, int64(start)); err != nil {
		return nil
	}
	return data
}

// fields reads big-endian unsigned fields of up to 8 bytes from b. After any
// read past the end, ok is false and further reads return 0.
type fields struct {
	b  []byte
	p  int
	ok bool
}

func (f *fields) uint(n int) uint64 {
	if !f.ok || n > 8 || f.p+n > len(f.b) {
		f.ok = false
		return 0
	}
	var v uint64
	for _, c := range f.b[f.p : f.p+n] {
		v = v<<8 | uint64(c)
	}
	f.p += n
	return v
}

// Grid is how FFmpeg (7.1 and later) exposes a tiled HEIF/AVIF image: tile
// streams placed at offsets on a canvas, cropped to the image size.
type Grid struct {
	Width, Height, Left, Top int
	Tiles                    []Tile
}

// Tile is one input stream of a Grid and its place on the canvas.
type Tile struct {
	Stream, X, Y int
}

// TileGrid asks ffprobe for the tile grid of the image open as f. It reports
// false when the image is not tiled or ffprobe predates stream groups; the
// first video stream is then the whole image as far as FFmpeg can tell.
func TileGrid(ctx context.Context, f *os.File) (Grid, bool) {
	ctx, cancel := context.WithTimeout(ctx, probeTimeout)
	defer cancel()
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return Grid{}, false
	}
	cmd := exec.CommandContext(ctx, "ffprobe", "-v", "error", "-show_stream_groups", "-of", "json", ExtraFilePath())
	cmd.ExtraFiles = []*os.File{f}
	out := &boundedBuffer{Limit: maxProbeOutput}
	cmd.Stdout = out
	if cmd.Run() != nil {
		return Grid{}, false
	}
	var data struct {
		Groups []struct {
			Type       string `json:"type"`
			Components []struct {
				Width            int `json:"width"`
				Height           int `json:"height"`
				HorizontalOffset int `json:"horizontal_offset"`
				VerticalOffset   int `json:"vertical_offset"`
				Subcomponents    []struct {
					Stream int `json:"stream_index"`
					X      int `json:"tile_horizontal_offset"`
					Y      int `json:"tile_vertical_offset"`
				} `json:"subcomponents"`
			} `json:"components"`
		} `json:"stream_groups"`
	}
	if json.Unmarshal(out.Bytes(), &data) != nil {
		return Grid{}, false
	}
	for _, g := range data.Groups {
		if g.Type != "Tile Grid" || len(g.Components) == 0 {
			continue
		}
		c := g.Components[0]
		grid := Grid{Width: c.Width, Height: c.Height, Left: c.HorizontalOffset, Top: c.VerticalOffset}
		for _, s := range c.Subcomponents {
			grid.Tiles = append(grid.Tiles, Tile{s.Stream, s.X, s.Y})
		}
		if grid.Width > 0 && grid.Height > 0 && len(grid.Tiles) > 0 {
			return grid, true
		}
	}
	return Grid{}, false
}

// Filter returns a filtergraph fragment that stitches the tiles of input 0
// and crops the canvas to the image, ready for further filters.
func (g Grid) Filter() string {
	var b strings.Builder
	layout := make([]string, len(g.Tiles))
	for i, t := range g.Tiles {
		fmt.Fprintf(&b, "[0:%d]", t.Stream)
		layout[i] = fmt.Sprintf("%d_%d", t.X, t.Y)
	}
	if len(g.Tiles) > 1 {
		fmt.Fprintf(&b, "xstack=inputs=%d:layout=%s:fill=black,", len(g.Tiles), strings.Join(layout, "|"))
	}
	fmt.Fprintf(&b, "crop=%d:%d:%d:%d", g.Width, g.Height, g.Left, g.Top)
	return b.String()
}
