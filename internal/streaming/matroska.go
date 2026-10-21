package streaming

import (
	"bytes"
	"context"
	"errors"
	"io"
	"math/bits"
	"time"
)

// Matroska element IDs, with their length marker bits kept as stored.
const (
	ebmlHeaderID   = 0x1A45DFA3
	segmentID      = 0x18538067
	seekHeadID     = 0x114D9B74
	seekID         = 0x4DBB
	seekIDID       = 0x53AB
	seekPositionID = 0x53AC
	cuesID         = 0x1C53BB6B
	clusterID      = 0x1F43B675

	maxSeekHeadSize = 1 << 20
)

// MatroskaRanges returns the Cues element of a Matroska/WebM file at its
// original offset. Media3 reads the top-level elements up to the first
// Cluster; Cues met on the way are read in place, otherwise it seeks to the
// Cues position a SeekHead names before it is prepared. It fails when neither
// is found within the search limits.
func MatroskaRanges(ctx context.Context, r io.ReaderAt, size int64) ([]Range, error) {
	deadline := time.Now().Add(indexSearchTime)
	id, head, length, err := ebmlElement(r, 0, size)
	if err != nil {
		return nil, err
	}
	if id != ebmlHeaderID || length < 0 {
		return nil, errors.New("not a Matroska file")
	}
	offset := head + length
	id, head, length, err = ebmlElement(r, offset, size)
	if err != nil {
		return nil, err
	}
	if id != segmentID {
		return nil, errors.New("Matroska segment not found")
	}
	start, end := offset+head, size
	if length >= 0 {
		end = start + length
	}
	cues := int64(-1)
	for offset, elements := start, 0; offset < end && elements < maxIndexBoxes; elements++ {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		if time.Now().After(deadline) {
			return nil, errors.New("index timeout")
		}
		id, head, length, err := ebmlElement(r, offset, end)
		if err != nil {
			return nil, err
		}
		if id == clusterID {
			break
		}
		if id == cuesID {
			cues = offset
			break
		}
		if length < 0 {
			return nil, errors.New("unknown-size element before the first cluster")
		}
		if id == seekHeadID {
			if length > maxSeekHeadSize {
				return nil, errors.New("SeekHead exceeds 1 MB")
			}
			data := make([]byte, length)
			if _, err := r.ReadAt(data, offset+head); err != nil {
				return nil, err
			}
			if position, ok := seekHeadCues(data); ok {
				cues = start + position
			}
		}
		offset += head + length
	}
	if cues < 0 {
		return nil, errors.New("Matroska Cues not found")
	}
	if cues >= size {
		return nil, errors.New("Cues position outside the file")
	}
	id, head, length, err = ebmlElement(r, cues, size)
	if err != nil {
		return nil, err
	}
	if id != cuesID || length < 0 {
		return nil, errors.New("SeekHead does not point to Cues")
	}
	if head+length > maxIndexSize {
		return nil, errors.New("index exceeds 32 MB")
	}
	return []Range{{Offset: cues, Length: head + length}}, nil
}

// seekHeadCues returns the last Cues position, relative to the segment data,
// that the Seek entries in a SeekHead's data name, as Media3 keeps the last.
func seekHeadCues(data []byte) (int64, bool) {
	r := bytes.NewReader(data)
	position, found := int64(0), false
	for offset := int64(0); offset < int64(len(data)); {
		id, head, length, err := ebmlElement(r, offset, int64(len(data)))
		if err != nil || length < 0 {
			return 0, false
		}
		if id == seekID {
			var target, at uint64
			var hasTarget, hasAt bool
			entry := data[offset+head : offset+head+length]
			er := bytes.NewReader(entry)
			for o := int64(0); o < int64(len(entry)); {
				cid, chead, clength, err := ebmlElement(er, o, int64(len(entry)))
				if err != nil || clength < 0 {
					return 0, false
				}
				value := entry[o+chead : o+chead+clength]
				if (cid == seekIDID || cid == seekPositionID) && clength > 8 {
					return 0, false
				}
				switch cid {
				case seekIDID:
					target, hasTarget = unsigned(value), true
				case seekPositionID:
					at, hasAt = unsigned(value), true
				}
				o += chead + clength
			}
			if hasTarget && hasAt && target == cuesID && at < 1<<62 {
				position, found = int64(at), true
			}
		}
		offset += head + length
	}
	return position, found
}

// ebmlElement reads the element header at offset within a parent ending at
// end. It returns the ID with its marker bits, the header length and the data
// length, which is -1 for an unknown size.
func ebmlElement(r io.ReaderAt, offset, end int64) (id uint32, head, length int64, err error) {
	var b [12]byte
	n := min(int64(len(b)), end-offset)
	if n < 2 {
		return 0, 0, 0, errors.New("truncated element")
	}
	if _, err := r.ReadAt(b[:n], offset); err != nil {
		return 0, 0, 0, err
	}
	idLength := int64(bits.LeadingZeros8(b[0]) + 1)
	if idLength > 4 || idLength >= n {
		return 0, 0, 0, errors.New("invalid element ID")
	}
	for _, c := range b[:idLength] {
		id = id<<8 | uint32(c)
	}
	sizeLength := int64(bits.LeadingZeros8(b[idLength]) + 1)
	if sizeLength > 8 || idLength+sizeLength > n {
		return 0, 0, 0, errors.New("invalid element size")
	}
	mask := byte(0xFF) >> sizeLength
	value := uint64(b[idLength] & mask)
	unknown := value == uint64(mask)
	for _, c := range b[idLength+1 : idLength+sizeLength] {
		value = value<<8 | uint64(c)
		unknown = unknown && c == 0xFF
	}
	head = idLength + sizeLength
	if unknown {
		return id, head, -1, nil
	}
	if value > uint64(end-offset-head) {
		return 0, 0, 0, errors.New("invalid element length")
	}
	return id, head, int64(value), nil
}

// unsigned decodes a big-endian EBML unsigned integer of at most 8 bytes.
func unsigned(b []byte) uint64 {
	var v uint64
	for _, c := range b {
		v = v<<8 | uint64(c)
	}
	return v
}
