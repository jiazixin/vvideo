package app

import (
	"net/http"
	"net/url"
	"slices"
	"strings"
)

const maxQueryBytes = 16 << 10

// validateQuery rejects ambiguous or malformed input before a handler reads it.
// Each route has a closed query vocabulary; aliases are not retained before release.
func validateQuery(r *http.Request) error {
	if len(r.URL.RawQuery) > maxQueryBytes {
		return invalidParameter("query", "too_long")
	}
	query, err := url.ParseQuery(r.URL.RawQuery)
	if err != nil {
		return invalidParameter("query", "invalid_encoding")
	}
	var allowed []string
	switch {
	case strings.HasSuffix(r.URL.Path, "/children"):
		allowed = []string{"cursor", "previous"}
	case strings.HasSuffix(r.URL.Path, "/media"):
		allowed = []string{"sort", "direction", "kind", "cursor", "previous", "anchor", "fallback", "limit"}
	case strings.HasSuffix(r.URL.Path, "/search"):
		allowed = []string{"q"}
	case strings.HasSuffix(r.URL.Path, "/bytes"), strings.HasSuffix(r.URL.Path, "/index"), strings.HasSuffix(r.URL.Path, "/thumbnail"):
		allowed = []string{"version"}
	case r.URL.Path == "/api/scans":
		allowed = []string{"force"}
	}
	for key, values := range query {
		if !slices.Contains(allowed, key) {
			return invalidParameter(key, "unknown_field")
		}
		if len(values) != 1 {
			return invalidParameter(key, "duplicate_field")
		}
		if (key == "previous" || key == "force") && values[0] != "true" && values[0] != "false" {
			return invalidParameter(key, "invalid_boolean")
		}
	}
	return nil
}
