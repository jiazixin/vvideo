package tests

import (
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"testing"

	"vvideo/internal/library"
)

func TestMediaPageEndAndReverseContinuation(t *testing.T) {
	index, scan, root := setup(t)
	for n := range 7 {
		if err := os.WriteFile(filepath.Join(root, fmt.Sprintf("%02d.mp4", n)), []byte("fixture"), 0o600); err != nil {
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
	for _, field := range []string{"name", "mtime", "capture"} {
		for _, direction := range []string{"asc", "desc"} {
			t.Run(field+"/"+direction, func(t *testing.T) {
				opts := library.ListOptions{Sort: field, Direction: direction, Kind: "video", Limit: 2}
				var ids []string
				var last library.Page
				for range 5 {
					page, err := index.List(t.Context(), libs[0].DirectoryID, opts)
					if err != nil {
						t.Fatal(err)
					}
					for _, item := range page.Items {
						if slices.Contains(ids, item.ID) {
							t.Fatal("forward paging repeated an item")
						}
						ids = append(ids, item.ID)
					}
					last = page
					if page.Next == "" {
						break
					}
					opts.Cursor = page.Next
				}
				if len(ids) != 7 || len(last.Items) != 1 || last.Next != "" {
					t.Fatalf("paging did not finish: count=%d last=%+v", len(ids), last)
				}
				opts.Cursor, opts.Previous = last.Previous, true
				prior, err := index.List(t.Context(), libs[0].DirectoryID, opts)
				if err != nil {
					t.Fatal(err)
				}
				if len(prior.Items) != 2 || prior.Items[0].ID != ids[4] || prior.Items[1].ID != ids[5] || prior.Next == "" {
					t.Fatalf("reverse page lost its forward continuation: %+v", prior)
				}
				opts.Cursor, opts.Previous = prior.Next, false
				end, err := index.List(t.Context(), libs[0].DirectoryID, opts)
				if err != nil {
					t.Fatal(err)
				}
				if len(end.Items) != 1 || end.Items[0].ID != ids[6] || end.Next != "" {
					t.Fatalf("forward continuation did not return to the end: %+v", end)
				}
				// An inclusive reverse anchor can itself contain the final item.
				opts.Cursor, opts.Anchor, opts.Previous = "", ids[6], true
				end, err = index.List(t.Context(), libs[0].DirectoryID, opts)
				if err != nil {
					t.Fatal(err)
				}
				if len(end.Items) != 2 || end.Items[1].ID != ids[6] || end.Next != "" {
					t.Fatalf("reverse anchor must omit an exhausted forward cursor: %+v", end)
				}
			})
		}
	}
}
