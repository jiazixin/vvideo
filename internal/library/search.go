package library

import (
	"cmp"
	"context"
	"database/sql"
	"fmt"
	"slices"
	"strings"
	"unicode/utf8"

	"vvideo/internal/storage"
)

const (
	// maxQueryLength bounds a search query, in characters.
	maxQueryLength = 100
	// searchDirectoryLimit and searchMediaLimit bound one search response.
	searchDirectoryLimit = 50
	searchMediaLimit     = 200
)

// SearchResult holds the folders and media whose names contain a query.
type SearchResult struct {
	Directories []Directory `json:"directories"`
	Items       []Entry     `json:"items"`
	// Truncated is true when more matches exist than were returned; the client
	// asks the user to refine the query instead of paging.
	Truncated bool `json:"truncated"`
}

// subtree is a recursive CTE of the visible directories below and including
// the directory bound to its parameter, at most maxDepth (1024) levels deep.
const subtree = `WITH RECURSIVE subtree(id,depth) AS (
	SELECT ?,0
	UNION ALL
	SELECT c.id,s.depth+1 FROM directories c JOIN subtree s ON c.parent_id=s.id
	WHERE c.state='visible' AND s.depth<1023
)`

// Search finds folders and published media in directory id and all its visible
// subfolders whose names contain query. Matching is a substring match that
// ignores case for ASCII letters only. Results are ordered by name and capped.
// Media matching stops at the cap instead of sorting every match, so a broad
// query returns some of its matches (Truncated) and the worst case is one pass
// over the names in the subtree. The join order and index are fixed: without
// statistics SQLite otherwise builds an automatic index over all entries on
// every query.
func (i *Index) Search(ctx context.Context, id int64, query string) (SearchResult, error) {
	r := SearchResult{Directories: []Directory{}, Items: []Entry{}}
	query = strings.TrimSpace(query)
	if query == "" || utf8.RuneCountInString(query) > maxQueryLength {
		return r, fmt.Errorf("%w: query must be 1..%d characters", ErrInvalid, maxQueryLength)
	}
	if err := i.Visible(ctx, id); err != nil {
		return r, err
	}
	pattern := "%" + escapeLike(query) + "%"
	tx, err := i.Store.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return r, err
	}
	defer tx.Rollback()

	rows, err := tx.QueryContext(ctx, subtree+`
		SELECT `+directoryColumns+`
		FROM subtree s JOIN directories d ON d.id=s.id
		WHERE s.id!=? AND d.name LIKE ? ESCAPE '\'
		ORDER BY d.name,d.id LIMIT ?`, id, id, pattern, searchDirectoryLimit+1)
	if r.Directories, err = storage.Collect(rows, err, scanDirectoryRow); err != nil {
		return r, err
	}

	rows, err = tx.QueryContext(ctx, subtree+`
		SELECT `+entryColumns+`
		FROM subtree s
		CROSS JOIN directories d ON d.id=s.id
		CROSS JOIN entries e INDEXED BY entry_name ON e.directory_id=d.id AND e.generation=d.generation
		JOIN versions v ON v.media_id=e.media_id AND v.version=e.version
		WHERE e.state='visible' AND e.name LIKE ? ESCAPE '\'
		LIMIT ?`, id, pattern, searchMediaLimit+1)
	if r.Items, err = storage.Collect(rows, err, scanEntryRow); err != nil {
		return r, err
	}

	if len(r.Directories) > searchDirectoryLimit {
		r.Directories, r.Truncated = r.Directories[:searchDirectoryLimit], true
	}
	if len(r.Items) > searchMediaLimit {
		r.Items, r.Truncated = r.Items[:searchMediaLimit], true
	}
	slices.SortFunc(r.Items, func(a, b Entry) int {
		return cmp.Or(cmp.Compare(a.Name, b.Name), cmp.Compare(a.ID, b.ID))
	})
	return r, tx.Commit()
}

var likeEscapes = strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)

// escapeLike escapes the LIKE wildcards in s for use with ESCAPE '\'.
func escapeLike(s string) string { return likeEscapes.Replace(s) }
