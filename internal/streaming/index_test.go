package streaming

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"slices"
	"testing"
)

// ebml encodes one element with an 8-byte size, or an unknown size when
// unknown is set.
func ebml(id uint32, unknown bool, children ...[]byte) []byte {
	var idBytes [4]byte
	binary.BigEndian.PutUint32(idBytes[:], id)
	out := bytes.TrimLeft(idBytes[:], "\x00")
	data := bytes.Join(children, nil)
	size := uint64(len(data)) | 1<<56
	if unknown {
		size = 1<<57 - 1
	}
	var sizeBytes [8]byte
	binary.BigEndian.PutUint64(sizeBytes[:], size)
	return slices.Concat(out, sizeBytes[:], data)
}

func element(id uint32, children ...[]byte) []byte { return ebml(id, false, children...) }

func uintBytes(v uint64) []byte {
	var b [8]byte
	binary.BigEndian.PutUint64(b[:], v)
	return b[:]
}

// seek is a SeekHead entry naming target at position in the segment data.
func seek(target uint32, position uint64) []byte {
	return element(seekID, element(seekIDID, uintBytes(uint64(target))[4:]), element(seekPositionID, uintBytes(position)))
}

// matroska lays out an EBML header and a segment whose SeekHead names the
// trailing Cues, like an ordinary muxer output. It returns the file and the
// Cues range.
func matroska(unknownSegment bool, entries func(cues uint64) []byte) ([]byte, Range) {
	header := element(ebmlHeaderID, element(0x4282, []byte("matroska")))
	info := element(0x1549A966, element(0x2AD7B1, uintBytes(1000000)))
	tracks := element(0x1654AE6B, element(0xAE, element(0xD7, []byte{1})))
	cluster := element(clusterID, element(0xE7, []byte{0}), make([]byte, 4096))
	cues := element(cuesID, element(0xBB, element(0xB3, []byte{0}), element(0xB7, element(0xF7, []byte{1}))))
	// The SeekHead's own length does not depend on the positions it holds.
	seekHead := element(seekHeadID, entries(0))
	position := uint64(len(seekHead) + len(info) + len(tracks) + len(cluster))
	seekHead = element(seekHeadID, entries(position))
	segment := ebml(segmentID, unknownSegment, seekHead, info, tracks, cluster, cues)
	file := slices.Concat(header, segment)
	return file, Range{Offset: int64(len(file) - len(cues)), Length: int64(len(cues))}
}

func cuesEntries(position uint64) []byte {
	// Padding inside an entry is legal and must not hide the entry.
	padded := element(seekID, element(0xEC, make([]byte, 32)), element(seekIDID, []byte{0x1C, 0x53, 0xBB, 0x6B}), element(seekPositionID, uintBytes(position)))
	return slices.Concat(seek(0x1549A966, 0), seek(0x1654AE6B, 0), padded)
}

// sparse reads data followed by zeros up to size, standing in for large files.
type sparse struct {
	data []byte
	size int64
}

func (s sparse) ReadAt(b []byte, offset int64) (int, error) {
	clear(b)
	if offset < int64(len(s.data)) {
		copy(b, s.data[offset:])
	}
	return len(b), nil
}

func TestTSRangesCoverDurationTail(t *testing.T) {
	for _, tc := range []struct {
		size int64
		want []Range
	}{
		// Sizes and offsets of the traced 08-h264.ts read on 2026-10-21.
		{1296448, []Range{{Offset: 1183648, Length: 112800}}},
		{112800, []Range{{Offset: 0, Length: 112800}}},
		{1000, []Range{{Offset: 0, Length: 1000}}},
		{0, []Range{}},
	} {
		if got := TSRanges(tc.size); !slices.Equal(got, tc.want) || got == nil {
			t.Fatalf("size %d: %+v", tc.size, got)
		}
	}
}

func TestMatroskaCuesFromSeekHead(t *testing.T) {
	for _, unknown := range []bool{false, true} {
		file, want := matroska(unknown, cuesEntries)
		got, err := MatroskaRanges(t.Context(), bytes.NewReader(file), int64(len(file)))
		if err != nil {
			t.Fatal(err)
		}
		if !slices.Equal(got, []Range{want}) {
			t.Fatalf("unknown segment size %v: %+v want %+v", unknown, got, want)
		}
		if !bytes.HasPrefix(file[want.Offset:], []byte{0x1C, 0x53, 0xBB, 0x6B}) || want.Offset+want.Length != int64(len(file)) {
			t.Fatal("range is not the trailing Cues element")
		}
	}
}

func TestMatroskaCuesBeforeFirstCluster(t *testing.T) {
	header, void := element(ebmlHeaderID), element(0xEC, make([]byte, 16))
	cues := element(cuesID, element(0xBB))
	file := slices.Concat(header, ebml(segmentID, true, void, cues, element(clusterID)))
	got, err := MatroskaRanges(t.Context(), bytes.NewReader(file), int64(len(file)))
	// The segment header is a 4-byte ID and an 8-byte size.
	want := Range{Offset: int64(len(header) + 12 + len(void)), Length: int64(len(cues))}
	if err != nil || !slices.Equal(got, []Range{want}) {
		t.Fatalf("%+v %v want %+v", got, err, want)
	}
}

func TestMatroskaMalformedFallsBack(t *testing.T) {
	valid, cues := matroska(true, cuesEntries)
	noCues, _ := matroska(true, func(uint64) []byte { return seek(0x1549A966, 0) })
	toSeekHead, _ := matroska(true, func(uint64) []byte { return seek(cuesID, 0) })
	outside, _ := matroska(true, func(uint64) []byte { return seek(cuesID, 1<<40) })
	unknownTracks := slices.Concat(element(ebmlHeaderID), ebml(segmentID, true, ebml(0x1654AE6B, true), element(clusterID)))
	bigSeekHead := slices.Concat(element(ebmlHeaderID), ebml(segmentID, true, element(seekHeadID, make([]byte, maxSeekHeadSize+1))))
	for name, file := range map[string][]byte{
		"empty":            nil,
		"not ebml":         []byte("not a matroska file"),
		"no cues entry":    noCues,
		"points to header": toSeekHead,
		"outside file":     outside,
		"truncated cues":   valid[:cues.Offset+cues.Length-1],
		"unknown tracks":   unknownTracks,
		"large seek head":  bigSeekHead,
		"zero id":          slices.Concat([]byte{0, 0x81}, valid),
	} {
		if got, err := MatroskaRanges(t.Context(), bytes.NewReader(file), int64(len(file))); err == nil {
			t.Fatalf("%s accepted: %+v", name, got)
		}
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if _, err := MatroskaRanges(ctx, bytes.NewReader(valid), int64(len(valid))); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}

func TestMatroskaOversizedCuesRejected(t *testing.T) {
	cluster := element(clusterID)
	// SeekHead positions are fixed-width, so its length does not depend on them.
	position := uint64(len(seekHeadWith(0)) + len(cluster))
	cuesHeader := slices.Concat([]byte{0x1C, 0x53, 0xBB, 0x6B, 0x01}, uintBytes(maxIndexSize)[1:])
	head := slices.Concat(element(ebmlHeaderID), ebml(segmentID, true, seekHeadWith(position), cluster), cuesHeader)
	file := sparse{data: head, size: int64(len(head)) + maxIndexSize}
	if got, err := MatroskaRanges(t.Context(), file, file.size); err == nil || err.Error() != "index exceeds 32 MB" {
		t.Fatalf("%+v %v", got, err)
	}
}

func seekHeadWith(position uint64) []byte {
	return element(seekHeadID, seek(cuesID, position))
}
