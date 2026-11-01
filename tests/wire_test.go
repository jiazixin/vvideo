package tests

import (
	"encoding/json/v2"
	"fmt"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"vvideo/internal/app"
	"vvideo/internal/jobs"
)

func TestServiceEndpointsAndWireFields(t *testing.T) {
	index, scan, root := setup(t)
	for _, name := range []string{"a.mp4", "b.mp4"} {
		if err := os.WriteFile(filepath.Join(root, name), []byte("fixture "+name), 0o600); err != nil {
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
	server := &app.App{Config: scan.Config, Index: index, Stream: app.NewStreamHandler(index, jobs.NewPriority(time.Now)), Version: "v1.0.0-test"}
	handler := server.Handler()
	get := func(path, token string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("GET", path, nil)
		if token != "" {
			r.Header.Set("Authorization", "Bearer "+token)
		}
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		return w
	}
	if w := get("/healthz", ""); w.Code != 200 || w.Body.String() != `{"status":"ok"}` || w.Header().Get("X-Request-ID") == "" {
		t.Fatalf("health %d %s", w.Code, w.Body.String())
	}
	if w := get("/api/info", ""); w.Code != 200 || !strings.Contains(w.Body.String(), `"version":"v1.0.0-test"`) {
		t.Fatalf("info %d %s", w.Code, w.Body.String())
	}
	if w := get("/api/libraries", "fixture"); w.Code != 200 || !strings.HasPrefix(w.Body.String(), `{"items":[`) {
		t.Fatalf("libraries %d %s", w.Code, w.Body.String())
	}
	w := get(fmt.Sprintf("/api/directories/%d/media?limit=1", libs[0].DirectoryID), "fixture")
	var page struct {
		Items []struct {
			SizeBytes   *int64 `json:"size_bytes"`
			ModifiedAt  string `json:"modified_at"`
			EffectiveAt string `json:"effective_at"`
		} `json:"items"`
		NextCursor string `json:"next_cursor"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &page); err != nil || w.Code != 200 || len(page.Items) != 1 {
		t.Fatalf("media page %d %s %v", w.Code, w.Body.String(), err)
	}
	stamp := regexp.MustCompile(`^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$`)
	item := page.Items[0]
	if item.SizeBytes == nil || !stamp.MatchString(item.ModifiedAt) || !stamp.MatchString(item.EffectiveAt) || page.NextCursor == "" {
		t.Fatalf("wire fields %s", w.Body.String())
	}
	w = get(fmt.Sprintf("/api/directories/%d/media?limit=1&cursor=%s", libs[0].DirectoryID, url.QueryEscape(page.NextCursor)), "fixture")
	var last map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &last); err != nil || w.Code != 200 {
		t.Fatalf("last page %d %s %v", w.Code, w.Body.String(), err)
	}
	if _, exists := last["next_cursor"]; exists {
		t.Fatalf("last page must omit next_cursor: %s", w.Body.String())
	}
	if w := get("/api/scans", "fixture"); w.Code != 405 || !strings.Contains(w.Body.String(), `"code":"method_not_allowed"`) {
		t.Fatalf("wrong method %d %s", w.Code, w.Body.String())
	}
}
