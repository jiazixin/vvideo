// Package streaming locates the original container index ranges a client may prefetch.
package streaming

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"time"
)

const (
	// Index search limits: a file whose index lies beyond these simply gets
	// no hints and plays through ordinary Range requests.
	maxIndexBoxes   = 10000
	indexSearchTime = 5 * time.Second
	maxFtypSize     = 1 << 20
	maxIndexSize    = 32 << 20

	// tsTailBytes is the transport-stream tail Media3's TsExtractor reads for
	// the last PCR before tracks are ready: its default timestamp search of
	// 600 packets of 188 bytes.
	tsTailBytes = 600 * 188
)

// Range is a byte range of the original file.
type Range struct {
	Offset int64 `json:"offset"`
	Length int64 `json:"length"`
}

// Hints lists the original-offset ranges worth prefetching for one version.
type Hints struct {
	Version string  `json:"version"`
	Size    int64   `json:"size"`
	Ranges  []Range `json:"ranges"`
}

// MP4Ranges walks the top-level boxes of an MP4/MOV file and returns the ftyp
// and moov ranges at their original offsets, so a client cache keyed by those
// offsets stays valid for the real file. It fails when no moov box is found
// within the search limits.
func MP4Ranges(ctx context.Context, r io.ReaderAt, size int64) ([]Range, error) {
	out := []Range{}
	deadline := time.Now().Add(indexSearchTime)
	for offset, boxes := int64(0), 0; offset < size && boxes < maxIndexBoxes; boxes++ {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		if time.Now().After(deadline) {
			return nil, errors.New("index timeout")
		}
		var header [16]byte
		if offset > size-8 {
			return nil, errors.New("truncated box")
		}
		if _, err := r.ReadAt(header[:8], offset); err != nil {
			return nil, err
		}
		length := int64(binary.BigEndian.Uint32(header[:4]))
		head := int64(8)
		if length == 1 {
			if offset > size-16 {
				return nil, errors.New("truncated large box")
			}
			if _, err := r.ReadAt(header[8:], offset+8); err != nil {
				return nil, err
			}
			n := binary.BigEndian.Uint64(header[8:])
			if n > uint64(size-offset) {
				return nil, errors.New("invalid box size")
			}
			length = int64(n)
			head = 16
		}
		if length == 0 {
			length = size - offset
		}
		if length < head || length > size-offset {
			return nil, errors.New("invalid box length")
		}
		kind := string(header[4:8])
		switch kind {
		case "ftyp":
			if length <= maxFtypSize {
				out = append(out, Range{Offset: offset, Length: length})
			}
		case "moov":
			if length > maxIndexSize {
				return nil, errors.New("index exceeds 32 MB")
			}
			out = append(out, Range{Offset: offset, Length: length})
			return out, nil
		}
		offset += length
	}
	return nil, errors.New("ordinary MP4 index not found")
}

// TSRanges returns the last min(size, 112,800) bytes of a transport stream,
// which Media3 reads for the duration before its tracks are ready. The range
// depends only on the indexed size, so no file read is needed.
func TSRanges(size int64) []Range {
	if size <= 0 {
		return []Range{}
	}
	n := min(size, tsTailBytes)
	return []Range{{Offset: size - n, Length: n}}
}
