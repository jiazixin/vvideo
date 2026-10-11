package tests

import (
	"encoding/json/v2"
	"fmt"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"vvideo/internal/app"
	"vvideo/internal/jobs"
	"vvideo/internal/streaming"
)

func TestChildrenBidirectionalWindow(t *testing.T) {
	for _, count := range []int{101, 200, 450} {
		t.Run(fmt.Sprint(count), func(t *testing.T) {
			index, scan, root := setup(t)
			for n := range count {
				if err := os.Mkdir(filepath.Join(root, fmt.Sprintf("folder-%04d", n)), 0700); err != nil {
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
			parent := libs[0].DirectoryID
			for _, order := range []string{"ASC", "DESC"} {
				comparison := ">"
				if order == "DESC" {
					comparison = "<"
				}
				rows, err := index.Store.Reader.QueryContext(t.Context(), "EXPLAIN QUERY PLAN SELECT id,name FROM directories WHERE parent_id=? AND state='visible' AND (name,id)"+comparison+"(?,?) ORDER BY name "+order+",id "+order+" LIMIT 101", parent, "folder-0200", 0)
				if err != nil {
					t.Fatal(err)
				}
				for rows.Next() {
					var a, b, c int
					var detail string
					if err := rows.Scan(&a, &b, &c, &detail); err != nil {
						t.Fatal(err)
					}
					if strings.Contains(detail, "TEMP B-TREE") || !strings.Contains(detail, "directory_children") {
						t.Fatalf("unbounded child plan: %s", detail)
					}
					t.Log(detail)
				}
				if err := rows.Err(); err != nil {
					t.Fatal(err)
				}
				rows.Close()
			}

			page, err := index.Children(t.Context(), parent, "")
			if err != nil {
				t.Fatal(err)
			}
			seen := 0
			for {
				for _, d := range page.Items {
					if d.Name != fmt.Sprintf("folder-%04d", seen) {
						t.Fatalf("forward %d: %s", seen, d.Name)
					}
					seen++
				}
				if page.Next == "" {
					break
				}
				page, err = index.Children(t.Context(), parent, page.Next)
				if err != nil {
					t.Fatal(err)
				}
			}
			if seen != count {
				t.Fatalf("got %d want %d", seen, count)
			}
			boundary := count - len(page.Items)
			for page.Previous != "" {
				page, err = index.Children(t.Context(), parent, page.Previous, true)
				if err != nil {
					t.Fatal(err)
				}
				boundary -= len(page.Items)
				for n, d := range page.Items {
					if d.Name != fmt.Sprintf("folder-%04d", boundary+n) {
						t.Fatalf("reverse boundary %d: %s", boundary, d.Name)
					}
				}
			}
			if boundary != 0 {
				t.Fatalf("unreachable start %d", boundary)
			}
			if _, err = index.Children(t.Context(), parent, "", true); err == nil {
				t.Fatal("empty reverse cursor accepted")
			}
		})
	}
}

func TestDirectoryMetadataHTTP(t *testing.T) {
	index, scan, _ := setup(t)
	libs, err := index.Libraries(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	server := &app.App{Config: scan.Config, Index: index, Stream: streaming.New(index, jobs.NewPriority())}
	handler := server.Handler()
	for _, tc := range []struct {
		id     int64
		token  string
		status int
	}{{libs[0].DirectoryID, "fixture", 200}, {libs[0].DirectoryID, "wrong", 401}, {-1, "fixture", 404}} {
		r := httptest.NewRequest("GET", fmt.Sprintf("/v1/directories/%d/path", tc.id), nil)
		r.Header.Set("Authorization", "Bearer "+tc.token)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		if w.Code != tc.status {
			t.Fatalf("status %d want %d", w.Code, tc.status)
		}
		if w.Code == 200 {
			var path []map[string]any
			if err := json.Unmarshal(w.Body.Bytes(), &path); err != nil {
				t.Fatal(err)
			}
			result := path[0]
			for _, private := range []string{"relative", "identity", "root"} {
				if _, ok := result[private]; ok {
					t.Fatalf("exposed %s", private)
				}
			}
		}
	}
}
