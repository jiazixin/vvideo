package app

import (
	"crypto/sha256"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"vvideo/internal/config"
)

func TestMalformedQueryIsRejected(t *testing.T) {
	t.Parallel()
	cfg := config.Config{}
	cfg.Auth.TokenDigest = sha256.Sum256([]byte("fixture"))
	handler := (&App{Config: cfg}).Handler()
	for _, tc := range []struct{ path, field, reason string }{
		{"/api/directories/1/children?previous=garbage", "previous", "invalid_boolean"},
		{"/api/directories/1/media?previous=", "previous", "invalid_boolean"},
		{"/api/scans?force=yes", "force", "invalid_boolean"},
		{"/api/directories/1/media?limit=1&limit=2", "limit", "duplicate_field"},
		{"/api/directories/1/media?old_limit=1", "old_limit", "unknown_field"},
		{"/api/directories/1/media?cursor=%zz", "query", "invalid_encoding"},
	} {
		t.Run(tc.path, func(t *testing.T) {
			t.Parallel()
			r := httptest.NewRequest(http.MethodGet, tc.path, nil)
			r.Header.Set("Authorization", "Bearer fixture")
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, r)
			if w.Code != http.StatusBadRequest || !strings.Contains(w.Body.String(), `"path":"`+tc.field+`"`) || !strings.Contains(w.Body.String(), `"reason":"`+tc.reason+`"`) {
				t.Fatalf("got %d %s, want invalid_argument for %s/%s", w.Code, w.Body, tc.field, tc.reason)
			}
		})
	}
}

func TestJSONResponsesDisableCachingAndCorrelateRequests(t *testing.T) {
	t.Parallel()
	handler := (&App{}).Handler()
	r := httptest.NewRequest(http.MethodGet, "/api/info", nil)
	r.Header.Set("X-Request-ID", "request-123")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, r)
	if w.Code != http.StatusOK || w.Header().Get("Cache-Control") != "no-store" || w.Header().Get("X-Request-ID") != "request-123" {
		t.Fatalf("got %d %v, want no-store and the supplied request ID", w.Code, w.Header())
	}
}
