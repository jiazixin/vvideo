package tests

import (
	"encoding/json/v2"
	"errors"
	"fmt"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"syscall"
	"testing"
	"unicode/utf8"

	"vvideo/internal/app"
	"vvideo/internal/jobs"
	"vvideo/internal/streaming"
)

// TestNonUTF8NamesStayListable: a Linux file system accepts names that are not
// valid UTF-8, such as GBK names copied from an old Windows disk. Such a name
// must not break the JSON of the page, search result or folder list it is in,
// and paging by name must continue past it.
func TestNonUTF8NamesStayListable(t *testing.T) {
	index, scan, root := setup(t)
	gbkDir, gbkFile := "\xc4\xbf\xc2\xbc\xb5\xc4", "\xb2\xe2\xca\xd4.mp4"
	if err := os.Mkdir(filepath.Join(root, gbkDir), 0700); err != nil {
		if errors.Is(err, syscall.EILSEQ) {
			t.Skip("this file system rejects names that are not UTF-8")
		}
		t.Fatal(err)
	}
	files := []string{"a.mp4", gbkFile, "z.mp4", "\xffclip.mp4"}
	for _, name := range append(files, filepath.Join(gbkDir, "inner.mp4")) {
		if err := os.WriteFile(filepath.Join(root, name), []byte(name), 0600); err != nil {
			t.Fatal(err)
		}
	}
	if err := scan.Run(t.Context(), false); err != nil {
		t.Fatal(err)
	}
	libs, err := index.Libraries(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	handler := (&app.App{Config: scan.Config, Index: index, Stream: streaming.New(index, jobs.NewPriority())}).Handler()
	get := func(path string, out any) {
		t.Helper()
		r := httptest.NewRequest("GET", path, nil)
		r.Header.Set("Authorization", "Bearer fixture")
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		if w.Code != 200 {
			t.Fatalf("%s: status %d: %s", path, w.Code, w.Body)
		}
		if err := json.Unmarshal(w.Body.Bytes(), out); err != nil {
			t.Fatalf("%s: invalid JSON %q: %v", path, w.Body, err)
		}
	}
	type named struct {
		ID   any    `json:"id"`
		Name string `json:"name"`
	}
	var children struct {
		Items []named `json:"items"`
	}
	get(fmt.Sprintf("/v1/directories/%d/children", libs[0].DirectoryID), &children)
	if len(children.Items) != 1 || !strings.ContainsRune(children.Items[0].Name, utf8.RuneError) {
		t.Fatalf("folder list %+v", children.Items)
	}
	// One item per page, so every name is a cursor key once.
	var seen []string
	cursor := ""
	for range len(files) + 1 {
		var page struct {
			Items []named `json:"items"`
			Next  string  `json:"next"`
		}
		get(fmt.Sprintf("/v1/directories/%d/media?sort=name&direction=asc&limit=1&cursor=%s", libs[0].DirectoryID, url.QueryEscape(cursor)), &page)
		if len(page.Items) == 0 {
			break
		}
		seen = append(seen, page.Items[0].Name)
		cursor = page.Next
	}
	want := slices.Sorted(slices.Values(files))
	for i, name := range want {
		want[i] = string([]rune(name)) // each invalid byte becomes U+FFFD
	}
	if !slices.Equal(seen, want) {
		t.Fatalf("paged names %q, want %q", seen, want)
	}
	var found struct {
		Items []named `json:"items"`
	}
	get(fmt.Sprintf("/v1/directories/%d/search?q=mp4", libs[0].DirectoryID), &found)
	if len(found.Items) != len(files)+1 {
		t.Fatalf("search found %d items: %+v", len(found.Items), found.Items)
	}
}
