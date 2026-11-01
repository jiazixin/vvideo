package app

import (
	"time"

	"vvideo/internal/library"
	"vvideo/internal/streaming"
	"vvideo/internal/timefmt"
)

// The types below are the JSON shapes of docs/API.md. Library types keep the
// index's own units (nanosecond times, byte counts); these name the units on
// the wire and send times as UTC RFC 3339 with milliseconds.

type libraryDTO struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	DirectoryID int64  `json:"directory_id"`
	Healthy     bool   `json:"healthy"`
}

type directoryDTO struct {
	ID        int64  `json:"id"`
	LibraryID string `json:"library_id"`
	ParentID  int64  `json:"parent_id,omitzero"`
	Name      string `json:"name"`
	Revision  int64  `json:"revision"`
}

type mediaDTO struct {
	ID             string       `json:"id"`
	Version        string       `json:"version"`
	DirectoryID    int64        `json:"directory_id"`
	LibraryID      string       `json:"library_id"`
	Name           string       `json:"name"`
	Kind           string       `json:"kind"`
	SizeBytes      int64        `json:"size_bytes"`
	Width          int          `json:"width"`
	Height         int          `json:"height"`
	Rotation       int          `json:"rotation,omitzero"`
	DurationMS     int64        `json:"duration_ms,omitzero"`
	ModifiedAt     timefmt.Time `json:"modified_at"`
	EffectiveAt    timefmt.Time `json:"effective_at"`
	CapturedRaw    string       `json:"captured_raw,omitempty"`
	CapturedSource string       `json:"captured_source,omitempty"`
	Status         string       `json:"status"`
}

type listDTO[T any] struct {
	Items []T `json:"items"`
}

type mediaPageDTO struct {
	Directory      directoryDTO `json:"directory"`
	Items          []mediaDTO   `json:"items"`
	NextCursor     string       `json:"next_cursor,omitempty"`
	PreviousCursor string       `json:"previous_cursor,omitempty"`
	Anchor         string       `json:"anchor,omitempty"`
	AnchorFound    bool         `json:"anchor_found"`
	Healthy        bool         `json:"healthy"`
}

type childrenPageDTO struct {
	Items          []directoryDTO `json:"items"`
	NextCursor     string         `json:"next_cursor,omitempty"`
	PreviousCursor string         `json:"previous_cursor,omitempty"`
}

type searchDTO struct {
	Directories []directoryDTO `json:"directories"`
	Items       []mediaDTO     `json:"items"`
	Truncated   bool           `json:"truncated"`
}

type rangeDTO struct {
	OffsetBytes int64 `json:"offset_bytes"`
	LengthBytes int64 `json:"length_bytes"`
}

type hintsDTO struct {
	Version   string     `json:"version"`
	SizeBytes int64      `json:"size_bytes"`
	Ranges    []rangeDTO `json:"ranges"`
}

func mapAll[T, U any](items []T, convert func(T) U) []U {
	out := make([]U, len(items))
	for i, item := range items {
		out[i] = convert(item)
	}
	return out
}

func libraryWire(l library.Library) libraryDTO {
	return libraryDTO{ID: l.ID, Name: l.Name, DirectoryID: l.DirectoryID, Healthy: l.Healthy}
}

func directoryWire(d library.Directory) directoryDTO {
	return directoryDTO{ID: d.ID, LibraryID: d.LibraryID, ParentID: d.ParentID, Name: d.Name, Revision: d.Revision}
}

func mediaWire(e library.Entry) mediaDTO {
	return mediaDTO{
		ID: e.ID, Version: e.Version, DirectoryID: e.DirectoryID, LibraryID: e.LibraryID,
		Name: e.Name, Kind: e.Kind, SizeBytes: e.Size, Width: e.Width, Height: e.Height,
		Rotation: e.Rotation, DurationMS: e.DurationMS,
		ModifiedAt:  timefmt.From(time.Unix(0, e.Mtime)),
		EffectiveAt: timefmt.From(time.Unix(0, e.EffectiveTime)),
		CapturedRaw: e.CapturedRaw, CapturedSource: e.CapturedSource, Status: e.Status,
	}
}

func mediaPageWire(p library.Page) mediaPageDTO {
	return mediaPageDTO{
		Directory: directoryWire(p.Directory), Items: mapAll(p.Items, mediaWire),
		NextCursor: p.Next, PreviousCursor: p.Previous, Anchor: p.Anchor,
		AnchorFound: p.AnchorFound, Healthy: p.Healthy,
	}
}

func childrenPageWire(p library.ChildrenPage) childrenPageDTO {
	return childrenPageDTO{Items: mapAll(p.Items, directoryWire), NextCursor: p.Next, PreviousCursor: p.Previous}
}

func searchWire(r library.SearchResult) searchDTO {
	return searchDTO{Directories: mapAll(r.Directories, directoryWire), Items: mapAll(r.Items, mediaWire), Truncated: r.Truncated}
}

func hintsWire(h streaming.Hints) hintsDTO {
	return hintsDTO{Version: h.Version, SizeBytes: h.Size, Ranges: mapAll(h.Ranges, func(r streaming.Range) rangeDTO {
		return rangeDTO{OffsetBytes: r.Offset, LengthBytes: r.Length}
	})}
}
