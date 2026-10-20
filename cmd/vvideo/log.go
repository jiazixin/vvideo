package main

import (
	"io"
	"log/slog"
	"os"
	"path/filepath"

	"vvideo/internal/config"
)

// newLogger builds the configured logger. A log file is appended to, never
// truncated or rotated, so external rotation must use copytruncate. close
// releases the file; it is a no-op for stdout and stderr.
func newLogger(c config.Config, stdout, stderr io.Writer) (logger *slog.Logger, close func() error, err error) {
	w, close := stdout, func() error { return nil }
	switch c.Log.Output {
	case "stderr":
		w = stderr
	case "file":
		if err = os.MkdirAll(filepath.Dir(c.Log.File), 0750); err != nil {
			return nil, nil, err
		}
		f, err := os.OpenFile(c.Log.File, os.O_WRONLY|os.O_APPEND|os.O_CREATE, 0640)
		if err != nil {
			return nil, nil, err
		}
		w, close = f, f.Close
	}
	options := &slog.HandlerOptions{Level: c.Log.Level}
	if c.Log.Format == "text" {
		return slog.New(slog.NewTextHandler(w, options)), close, nil
	}
	return slog.New(slog.NewJSONHandler(w, options)), close, nil
}
