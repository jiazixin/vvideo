package probe

import (
	"bytes"
	"encoding/binary"
	"io"
)

// Exif reads at most 256 KiB of JPEG header/TIFF metadata. Malformed tags simply
// retain the raw unknown-time semantics and never trigger an unbounded read.
func Exif(reader io.Reader) (raw, offset string, rotation int) {
	b, _ := io.ReadAll(io.LimitReader(reader, 256<<10))
	if len(b) < 4 || b[0] != 0xff || b[1] != 0xd8 {
		return
	}
	for p := 2; p+4 <= len(b); {
		if b[p] != 0xff {
			return
		}
		marker := b[p+1]
		if marker == 0xda || marker == 0xd9 {
			return
		}
		size := int(binary.BigEndian.Uint16(b[p+2 : p+4]))
		if size < 2 || p+2+size > len(b) {
			return
		}
		if marker == 0xe1 && size > 8 && bytes.Equal(b[p+4:p+10], []byte("Exif\x00\x00")) {
			return tiff(b[p+10 : p+2+size])
		}
		p += 2 + size
	}
	return
}
func tiff(b []byte) (raw, offset string, rotation int) {
	if len(b) < 8 {
		return
	}
	var order binary.ByteOrder
	switch string(b[:2]) {
	case "II":
		order = binary.LittleEndian
	case "MM":
		order = binary.BigEndian
	default:
		return
	}
	if order.Uint16(b[2:4]) != 42 {
		return
	}
	next := order.Uint32(b[4:8])
	visited := map[uint32]bool{}
	for depth := 0; next != 0 && depth < 4; depth++ {
		if visited[next] || int64(next)+2 > int64(len(b)) {
			break
		}
		visited[next] = true
		p := int(next)
		n := int(order.Uint16(b[p : p+2]))
		if n > 1024 || p+2+12*n > len(b) {
			break
		}
		next = 0
		for j := range n {
			entry := b[p+2+12*j : p+14+12*j]
			tag := order.Uint16(entry[:2])
			kind := order.Uint16(entry[2:4])
			count := int(order.Uint32(entry[4:8]))
			if tag == 0x8769 && kind == 4 && count == 1 {
				next = order.Uint32(entry[8:12])
			}
			if tag == 0x0112 && kind == 3 && count == 1 {
				switch order.Uint16(entry[8:10]) {
				case 3:
					rotation = 180
				case 6:
					rotation = 90
				case 8:
					rotation = 270
				}
			}
			if (tag == 0x9003 || tag == 0x9011) && kind == 2 && count > 0 && count <= 128 {
				value := entry[8:12]
				if count > 4 {
					start := int(order.Uint32(entry[8:12]))
					if start < 0 || start+count > len(b) {
						continue
					}
					value = b[start : start+count]
				} else {
					value = value[:count]
				}
				v := string(bytes.TrimRight(value, "\x00"))
				if tag == 0x9003 {
					raw = v
				} else {
					offset = v
				}
			}
		}
	}
	return
}
