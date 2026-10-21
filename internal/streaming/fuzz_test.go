package streaming

import (
	"bytes"
	"context"
	"encoding/binary"
	"slices"
	"testing"
)

// box encodes one MP4 box with a 32-bit size.
func box(kind string, data []byte) []byte {
	var head [8]byte
	binary.BigEndian.PutUint32(head[:4], uint32(8+len(data)))
	copy(head[4:], kind)
	return slices.Concat(head[:], data)
}

// FuzzMP4Ranges feeds arbitrary bytes to the box walker: it must never panic,
// and any ranges it returns must lie inside the file, in order, ending with
// the moov box.
func FuzzMP4Ranges(f *testing.F) {
	f.Add(slices.Concat(box("ftyp", []byte("isom")), box("moov", make([]byte, 16))))
	f.Add(slices.Concat(box("ftyp", []byte("isom")), box("mdat", make([]byte, 64)), box("moov", nil)))
	large := []byte{0, 0, 0, 1, 'm', 'o', 'o', 'v', 0, 0, 0, 0, 0, 0, 0, 24, 1, 2, 3, 4, 5, 6, 7, 8}
	f.Add(large)
	f.Add([]byte{0, 0, 0, 0, 'm', 'd', 'a', 't'})
	f.Fuzz(func(t *testing.T, data []byte) {
		size := int64(len(data))
		ranges, err := MP4Ranges(context.Background(), bytes.NewReader(data), size)
		if err != nil {
			return
		}
		if len(ranges) == 0 {
			t.Fatal("success without ranges")
		}
		previous := int64(-1)
		for _, r := range ranges {
			if r.Offset <= previous || r.Length < 8 || r.Offset+r.Length > size {
				t.Fatalf("range %+v outside file of %d bytes or out of order", r, size)
			}
			previous = r.Offset
		}
		last := ranges[len(ranges)-1]
		if string(data[last.Offset+4:last.Offset+8]) != "moov" {
			t.Fatalf("last range %+v is not moov", last)
		}
	})
}

// FuzzMatroskaRanges feeds arbitrary bytes to the EBML walker: it must never
// panic, and a returned range must be one whole Cues element inside the file.
func FuzzMatroskaRanges(f *testing.F) {
	cues := element(cuesID, []byte{1, 2, 3})
	header := element(ebmlHeaderID)
	f.Add(slices.Concat(header, element(segmentID, cues, element(clusterID))))
	headSize := len(element(seekHeadID, seek(cuesID, 0)))
	f.Add(slices.Concat(header, element(segmentID, element(seekHeadID, seek(cuesID, uint64(headSize+len(element(clusterID))))), element(clusterID), cues)))
	f.Add(slices.Concat(header, ebml(segmentID, true, element(seekHeadID, seek(cuesID, 3)), element(clusterID))))
	f.Add([]byte{0x1A, 0x45, 0xDF, 0xA3, 0x80, 0x18, 0x53, 0x80, 0x67, 0xFF})
	f.Fuzz(func(t *testing.T, data []byte) {
		size := int64(len(data))
		ranges, err := MatroskaRanges(context.Background(), bytes.NewReader(data), size)
		if err != nil {
			return
		}
		if len(ranges) != 1 {
			t.Fatalf("want one range, got %+v", ranges)
		}
		r := ranges[0]
		if r.Offset < 0 || r.Length < 2 || r.Offset+r.Length > size {
			t.Fatalf("range %+v outside file of %d bytes", r, size)
		}
		id, head, length, err := ebmlElement(bytes.NewReader(data), r.Offset, size)
		if err != nil || id != cuesID || head+length != r.Length {
			t.Fatalf("range %+v is not one Cues element: id=%x head=%d length=%d err=%v", r, id, head, length, err)
		}
	})
}
