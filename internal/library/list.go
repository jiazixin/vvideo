package library

import (
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json/v2"
	"errors"
	"fmt"
	"slices"
)

const (
	// defaultPageSize and maxPageSize bound one media page.
	defaultPageSize = 100
	maxPageSize     = 200
	// childPageSize is the fixed page size of subdirectory listings.
	childPageSize = 100
	// maxCursorLength rejects oversized cursors before decoding them.
	maxCursorLength = 4096
)

// sortColumns maps a sort name to its entries column.
var sortColumns = map[string]string{"capture": "effective_time", "mtime": "mtime", "name": "name"}

// Cursor is the opaque paging position handed to clients. It pins the
// directory revision and listing options, so a stale cursor fails with
// ErrConflict instead of silently mixing two orders.
type Cursor struct {
	DirectoryID           int64
	Revision              int64
	Sort, Direction, Kind string
	Key, ID               string
}

// cursorWire is Cursor as encoded. Key travels as bytes because a name key is
// the raw file name, which need not be valid UTF-8 and must round-trip exactly.
type cursorWire struct {
	DirectoryID int64  `json:"directory_id"`
	Revision    int64  `json:"revision"`
	Sort        string `json:"sort"`
	Direction   string `json:"direction"`
	Kind        string `json:"kind"`
	Key         []byte `json:"key"`
	ID          string `json:"id"`
}

// EncodeCursor serializes c as URL-safe base64 JSON.
func EncodeCursor(c Cursor) string {
	b, _ := json.Marshal(cursorWire{c.DirectoryID, c.Revision, c.Sort, c.Direction, c.Kind, []byte(c.Key), c.ID})
	return base64.RawURLEncoding.EncodeToString(b)
}

// DecodeCursor parses a cursor made by EncodeCursor.
func DecodeCursor(s string) (Cursor, error) {
	var c Cursor
	errCursor := fmt.Errorf("%w: malformed cursor", ErrInvalid)
	if len(s) > maxCursorLength {
		return c, errCursor
	}
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		return c, errCursor
	}
	var w cursorWire
	if err = json.Unmarshal(b, &w, json.RejectUnknownMembers(true)); err != nil {
		return c, errCursor
	}
	return Cursor{w.DirectoryID, w.Revision, w.Sort, w.Direction, w.Kind, string(w.Key), w.ID}, nil
}

// ListOptions are the query parameters of a media listing.
type ListOptions struct {
	// Sort is "capture" (default), "mtime" or "name"; Direction is "asc" or
	// "desc" (default); Kind optionally filters to "video" or "image".
	Sort, Direction, Kind string
	// Cursor continues after (or, with Previous, before) an earlier page.
	Cursor   string
	Previous bool
	// Anchor starts the page at the album group containing this media ID.
	// Fallback is the cursor to use when the anchor no longer exists.
	Anchor, Fallback string
	Limit            int
}

// Page is one page of a media listing.
type Page struct {
	Directory   Directory `json:"directory"`
	Items       []Entry   `json:"items"`
	Next        string    `json:"next"`
	Previous    string    `json:"previous"`
	Anchor      string    `json:"anchor"`
	AnchorFound bool      `json:"anchor_found"`
	Healthy     bool      `json:"healthy"`
}

func (o *ListOptions) normalize() error {
	if o.Sort == "" {
		o.Sort = "capture"
	}
	if o.Direction == "" {
		o.Direction = "desc"
	}
	if o.Limit == 0 {
		o.Limit = defaultPageSize
	}
	if o.Limit < 1 || o.Limit > maxPageSize {
		return fmt.Errorf("%w: limit must be 1..%d", ErrInvalid, maxPageSize)
	}
	if o.Sort != "capture" && o.Sort != "mtime" && o.Sort != "name" {
		return fmt.Errorf("%w: sort must be capture, mtime or name", ErrInvalid)
	}
	if o.Direction != "asc" && o.Direction != "desc" {
		return fmt.Errorf("%w: direction must be asc or desc", ErrInvalid)
	}
	if o.Kind != "" && o.Kind != "video" && o.Kind != "image" {
		return fmt.Errorf("%w: kind must be video or image", ErrInvalid)
	}
	return nil
}

// key is e's cursor key for sort.
func key(e Entry, sort string) string {
	switch sort {
	case "name":
		return e.Name
	case "mtime":
		return fmt.Sprint(e.Mtime)
	default:
		return fmt.Sprint(e.EffectiveTime)
	}
}

// List returns one page of directory id's visible media in the requested order.
// Ties are broken by media ID, so paging never repeats or skips an item.
func (i *Index) List(ctx context.Context, id int64, o ListOptions) (Page, error) {
	var p Page
	if err := o.normalize(); err != nil {
		return p, err
	}
	if err := i.Visible(ctx, id); err != nil {
		return p, err
	}
	tx, err := i.Store.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return p, err
	}
	defer tx.Rollback()
	if err = tx.QueryRowContext(ctx, "SELECT id,library_id,coalesce(parent_id,0),relative,name,identity,generation,revision,state FROM directories WHERE id=?", id).Scan(&p.Directory.ID, &p.Directory.LibraryID, &p.Directory.ParentID, &p.Directory.Relative, &p.Directory.Name, &p.Directory.Identity, &p.Directory.Generation, &p.Directory.Revision, &p.Directory.State); err != nil {
		return p, err
	}
	if err = tx.QueryRowContext(ctx, "SELECT healthy FROM libraries WHERE id=?", p.Directory.LibraryID).Scan(&p.Healthy); err != nil {
		return p, err
	}
	col := sortColumns[o.Sort]
	dir := o.Direction
	comparison := ">"
	if dir == "desc" {
		comparison = "<"
	}
	if o.Previous {
		if dir == "asc" {
			dir = "desc"
			comparison = "<"
		} else {
			dir = "asc"
			comparison = ">"
		}
	}
	filter := "e.directory_id=? AND e.generation=? AND e.state='visible'"
	args := []any{id, p.Directory.Generation}
	if o.Kind != "" {
		filter += " AND e.kind=?"
		args = append(args, o.Kind)
	}
	var c Cursor
	inclusive := false
	if o.Cursor != "" {
		c, err = DecodeCursor(o.Cursor)
		if err != nil {
			return p, err
		}
		if c.DirectoryID != id || c.Revision != p.Directory.Revision || c.Sort != o.Sort || c.Direction != o.Direction || c.Kind != o.Kind {
			return p, ErrConflict
		}
	}
	if o.Anchor != "" {
		anchor, err := ScanEntry(tx.QueryRowContext(ctx, `SELECT `+entryColumns+` FROM entries e JOIN directories d ON d.id=e.directory_id JOIN versions v ON v.media_id=e.media_id AND v.version=e.version WHERE e.directory_id=? AND e.generation=? AND e.media_id=? AND e.state='visible'`, id, p.Directory.Generation, o.Anchor))
		if err == nil {
			start, groupErr := i.groupStart(ctx, tx, p.Directory, o, anchor)
			if groupErr != nil {
				return p, groupErr
			}
			c = start
			inclusive = true
			p.Anchor = anchor.ID
			p.AnchorFound = true
		} else if !errors.Is(err, sql.ErrNoRows) {
			return p, err
		} else if o.Fallback != "" {
			fallback, e := DecodeCursor(o.Fallback)
			if e == nil && fallback.DirectoryID == id && fallback.Sort == o.Sort && fallback.Direction == o.Direction && fallback.Kind == o.Kind {
				c = fallback
				inclusive = true
			}
		}
	}
	if c.ID != "" {
		cmp := comparison
		if inclusive {
			cmp += "="
		}
		filter += fmt.Sprintf(" AND (e.%s,e.media_id) %s (?,?)", col, cmp)
		args = append(args, c.Key, c.ID)
	}
	args = append(args, o.Limit+1)
	rows, err := tx.QueryContext(ctx, `SELECT `+entryColumns+` FROM entries e JOIN directories d ON d.id=e.directory_id JOIN versions v ON v.media_id=e.media_id AND v.version=e.version WHERE `+filter+` ORDER BY e.`+col+` `+dir+`,e.media_id `+dir+` LIMIT ?`, args...)
	if err != nil {
		return p, err
	}
	p.Items = []Entry{}
	for rows.Next() {
		e, err := ScanEntry(rows)
		if err != nil {
			rows.Close()
			return p, err
		}
		p.Items = append(p.Items, e)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return p, err
	}
	if len(p.Items) > o.Limit {
		p.Items = p.Items[:o.Limit]
	}
	if o.Previous {
		slices.Reverse(p.Items)
	}
	if len(p.Items) > 0 {
		cursor := func(e Entry) string {
			return EncodeCursor(Cursor{DirectoryID: id, Revision: p.Directory.Revision, Sort: o.Sort, Direction: o.Direction, Kind: o.Kind, Key: key(e, o.Sort), ID: e.ID})
		}
		p.Previous = cursor(p.Items[0])
		p.Next = cursor(p.Items[len(p.Items)-1])
	}
	return p, tx.Commit()
}

// ChildrenPage is one page of subdirectories.
type ChildrenPage struct {
	Items    []Directory `json:"items"`
	Next     string      `json:"next"`
	Previous string      `json:"previous"`
}

// Children pages subdirectories by name and ID. previous returns the page
// before cursor, still in ascending order.
func (i *Index) Children(ctx context.Context, parent int64, cursor string, previous bool) (ChildrenPage, error) {
	p := ChildrenPage{Items: []Directory{}}
	if err := i.Visible(ctx, parent); err != nil {
		return p, err
	}
	if previous && cursor == "" {
		return p, fmt.Errorf("%w: previous requires a cursor", ErrInvalid)
	}
	name, id := "", int64(0)
	if cursor != "" {
		c, err := DecodeCursor(cursor)
		if err != nil || c.DirectoryID != parent {
			return p, fmt.Errorf("%w: malformed child cursor", ErrInvalid)
		}
		name = c.Key
		_, err = fmt.Sscan(c.ID, &id)
		if err != nil {
			return p, fmt.Errorf("%w: malformed child cursor", ErrInvalid)
		}
	}
	comparison, order := ">", "ASC"
	if previous {
		comparison, order = "<", "DESC"
	}
	rows, err := i.Store.Reader.QueryContext(ctx, "SELECT id,library_id,coalesce(parent_id,0),relative,name,identity,generation,revision,state FROM directories WHERE parent_id=? AND state='visible' AND (name,id)"+comparison+"(?,?) ORDER BY name "+order+",id "+order+" LIMIT ?", parent, name, id, childPageSize+1)
	if err != nil {
		return p, err
	}
	defer rows.Close()
	for rows.Next() {
		var d Directory
		if err = rows.Scan(&d.ID, &d.LibraryID, &d.ParentID, &d.Relative, &d.Name, &d.Identity, &d.Generation, &d.Revision, &d.State); err != nil {
			return p, err
		}
		p.Items = append(p.Items, d)
	}
	more := len(p.Items) > childPageSize
	if more {
		p.Items = p.Items[:childPageSize]
	}
	if previous {
		slices.Reverse(p.Items)
	}
	if len(p.Items) > 0 {
		first, last := p.Items[0], p.Items[len(p.Items)-1]
		if (!previous && more) || previous {
			p.Next = EncodeCursor(Cursor{DirectoryID: parent, Key: last.Name, ID: fmt.Sprint(last.ID)})
		}
		if (previous && more) || (!previous && cursor != "") {
			p.Previous = EncodeCursor(Cursor{DirectoryID: parent, Key: first.Name, ID: fmt.Sprint(first.ID)})
		}
	}
	return p, rows.Err()
}
