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
	"strconv"
	"testing"
	"time"

	"vvideo/internal/app"
	"vvideo/internal/jobs"
	"vvideo/internal/library"
	"vvideo/internal/streaming"
)

func TestSearchFindsNamesInTheFolderSubtree(t *testing.T) {
	index, scan, root := setup(t)
	for _, dir := range []string{"Trip 2024/Beach days", "Trip 2024/day1", "other"} {
		if err := os.MkdirAll(filepath.Join(root, dir), 0700); err != nil {
			t.Fatal(err)
		}
	}
	for _, file := range []string{
		"Trip 2024/day1/clip-BEACH.mp4",
		"Trip 2024/beach_sunset.jpg",
		"Trip 2024/100%.mp4",
		"Trip 2024/海边日落.mp4",
		"Trip 2024/notes.txt",
		"other/beach.mp4",
	} {
		if err := os.WriteFile(filepath.Join(root, file), []byte(file), 0600); err != nil {
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
	children, err := index.Children(t.Context(), libs[0].DirectoryID, "", false)
	if err != nil {
		t.Fatal(err)
	}
	trip := children.Items[slices.IndexFunc(children.Items, func(d library.Directory) bool { return d.Name == "Trip 2024" })]

	names := func(r library.SearchResult) (dirs, items []string) {
		for _, d := range r.Directories {
			dirs = append(dirs, d.Name)
		}
		for _, e := range r.Items {
			items = append(items, e.Name)
		}
		return dirs, items
	}
	for _, tc := range []struct {
		query        string
		dirs, items  []string
		fromLibrary  bool
		wantInvalid  bool
		wantTruncate bool
	}{
		// Case-insensitive for ASCII, includes subfolders, excludes the sibling "other".
		{query: "beach", dirs: []string{"Beach days"}, items: []string{"beach_sunset.jpg", "clip-BEACH.mp4"}},
		{query: "beach", fromLibrary: true, dirs: []string{"Beach days"}, items: []string{"beach.mp4", "beach_sunset.jpg", "clip-BEACH.mp4"}},
		{query: "海边", items: []string{"海边日落.mp4"}},
		// LIKE wildcards are literal.
		{query: "%", items: []string{"100%.mp4"}},
		{query: "_", items: []string{"beach_sunset.jpg"}},
		// Files that are not indexed never match.
		{query: "notes"},
		{query: "  ", wantInvalid: true},
	} {
		id := trip.ID
		if tc.fromLibrary {
			id = libs[0].DirectoryID
		}
		r, err := index.Search(t.Context(), id, tc.query)
		if tc.wantInvalid {
			if !errors.Is(err, library.ErrInvalid) {
				t.Fatalf("query %q: error %v, want invalid", tc.query, err)
			}
			continue
		}
		if err != nil {
			t.Fatalf("query %q: %v", tc.query, err)
		}
		dirs, items := names(r)
		if !slices.Equal(dirs, tc.dirs) || !slices.Equal(items, tc.items) || r.Truncated {
			t.Fatalf("query %q: dirs %q items %q truncated %v; want %q %q", tc.query, dirs, items, r.Truncated, tc.dirs, tc.items)
		}
	}

	server := &app.App{Config: scan.Config, Index: index, Stream: streaming.New(index, jobs.NewPriority())}
	for _, tc := range []struct {
		query  string
		status int
	}{{"BEACH", 200}, {"", 400}} {
		r := httptest.NewRequest("GET", fmt.Sprintf("/v1/directories/%d/search?q=%s", trip.ID, url.QueryEscape(tc.query)), nil)
		r.Header.Set("Authorization", "Bearer fixture")
		w := httptest.NewRecorder()
		server.Handler().ServeHTTP(w, r)
		if w.Code != tc.status {
			t.Fatalf("query %q: status %d want %d: %s", tc.query, w.Code, tc.status, w.Body)
		}
		if w.Code == 200 {
			var body map[string]any
			if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
				t.Fatal(err)
			}
			if len(body["items"].([]any)) != 2 || len(body["directories"].([]any)) != 1 {
				t.Fatalf("unexpected body %s", w.Body)
			}
		}
	}
}

// TestSearchLargeFolderIsCapped seeds one folder (VVIDEO_SCALE_ITEMS entries,
// 5000 by default) and checks that a broad query is capped and truncated.
func TestSearchLargeFolderIsCapped(t *testing.T) {
	index, _, _ := setup(t)
	libs, err := index.Libraries(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	d, err := index.Directory(t.Context(), libs[0].DirectoryID)
	if err != nil {
		t.Fatal(err)
	}
	n := 5000
	if x := os.Getenv("VVIDEO_SCALE_ITEMS"); x != "" {
		if n, err = strconv.Atoi(x); err != nil || n < 1 || n > 510000 {
			t.Fatal("invalid scale")
		}
	}
	gen, err := index.BeginBuild(t.Context(), d)
	if err != nil {
		t.Fatal(err)
	}
	for offset := 0; offset < n; offset += 128 {
		batch := make([]library.Entry, 0, 128)
		for j := offset; j < min(n, offset+128); j++ {
			batch = append(batch, library.Entry{
				ID: fmt.Sprintf("m%08d", j), Version: fmt.Sprintf("v%08d", j), Name: fmt.Sprintf("clip %08d.mp4", j),
				Kind: "video", Fingerprint: fmt.Sprint(j), Size: 1, NewVersion: true, Present: true, State: "visible",
			})
		}
		if err = index.AddBatch(t.Context(), d, gen, batch, time.Now()); err != nil {
			t.Fatal(err)
		}
	}
	if err = index.Publish(t.Context(), d, gen, "fixture", 1, func() error { return nil }); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		query     string
		items     int
		truncated bool
	}{{"clip", 200, true}, {fmt.Sprintf("%08d", n-1), 1, false}, {"absent", 0, false}} {
		start := time.Now()
		r, err := index.Search(t.Context(), d.ID, tc.query)
		if err != nil {
			t.Fatal(err)
		}
		t.Logf("items=%d query=%q matches=%d elapsed=%s", n, tc.query, len(r.Items), time.Since(start))
		if len(r.Items) != min(tc.items, n) || r.Truncated != (tc.truncated && n > 200) {
			t.Fatalf("query %q: %d items truncated %v", tc.query, len(r.Items), r.Truncated)
		}
	}
}
