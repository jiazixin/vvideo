package main

import (
	"bytes"
	"log/slog"
	"os"
	"strings"
	"testing"

	"vvideo/internal/config"
)

func TestParseArgs(t *testing.T) {
	for _, tc := range []struct {
		args []string
		want options
	}{
		{nil, options{}},
		{[]string{"-c", "a.yaml"}, options{configFile: "a.yaml"}},
		{[]string{"--config", "a.yaml"}, options{configFile: "a.yaml"}},
		{[]string{"-c=a.yaml"}, options{configFile: "a.yaml"}},
		{[]string{"--config=a.yaml"}, options{configFile: "a.yaml"}},
		{[]string{"-c", "a.yaml", "--config", "b.yaml"}, options{configFile: "b.yaml"}},
		{[]string{"-h"}, options{help: true}},
		{[]string{"--help"}, options{help: true}},
		{[]string{"-v"}, options{version: true}},
		{[]string{"--version"}, options{version: true}},
	} {
		t.Run(strings.Join(tc.args, " "), func(t *testing.T) {
			got, err := parseArgs(tc.args)
			if err != nil || got != tc.want {
				t.Fatalf("got %+v, %v; want %+v", got, err, tc.want)
			}
		})
	}
}

func TestUsageErrors(t *testing.T) {
	for _, tc := range []struct {
		args []string
		want string
	}{
		{[]string{"-c"}, "flag needs an argument"},
		{[]string{"--config="}, "must not be empty"},
		{[]string{"-c", " "}, "must not be empty"},
		{[]string{"--bogus"}, "not defined: -bogus"},
		{[]string{"config.yaml"}, `unexpected argument "config.yaml"`},
		{[]string{"--", "config.yaml"}, `unexpected argument "config.yaml"`},
	} {
		t.Run(strings.Join(tc.args, " "), func(t *testing.T) {
			var stdout, stderr bytes.Buffer
			if code := run(tc.args, &stdout, &stderr); code != exitUsage {
				t.Fatalf("exit %d, want %d", code, exitUsage)
			}
			if !strings.Contains(stderr.String(), tc.want) || !strings.Contains(stderr.String(), "vvideo --help") {
				t.Fatalf("stderr %q does not explain %q", stderr.String(), tc.want)
			}
			if stdout.Len() != 0 {
				t.Fatal("usage error wrote to stdout")
			}
		})
	}
}

func TestHelpAndVersion(t *testing.T) {
	for _, tc := range []struct {
		arg  string
		want []string
	}{
		{"--help", []string{"Usage:", "-c, --config <file>", "-v, --version", "-h, --help", "CONFIG_FILE", "VVIDEO_AUTH__TOKEN", "libwebp"}},
		{"--version", []string{"vvideo ", "go1."}},
	} {
		t.Run(tc.arg, func(t *testing.T) {
			var stdout, stderr bytes.Buffer
			if code := run([]string{tc.arg}, &stdout, &stderr); code != exitOK {
				t.Fatalf("exit %d", code)
			}
			for _, want := range tc.want {
				if !strings.Contains(stdout.String(), want) {
					t.Errorf("output is missing %q:\n%s", want, stdout.String())
				}
			}
			if stderr.Len() != 0 {
				t.Fatalf("unexpected stderr %q", stderr.String())
			}
		})
	}
}

func TestConfigErrorExitsBeforeStartup(t *testing.T) {
	var stdout, stderr bytes.Buffer
	if code := run([]string{"--config", t.TempDir() + "/missing.yaml"}, &stdout, &stderr); code != exitFailure {
		t.Fatalf("exit %d, want %d", code, exitFailure)
	}
	if !strings.Contains(stdout.String(), "missing.yaml") || !strings.Contains(stdout.String(), "service_failed") {
		t.Fatalf("log does not name the missing file: %s", stdout.String())
	}
	if strings.Contains(stdout.String(), "service_starting") {
		t.Fatal("service started despite invalid configuration")
	}
}

func TestLoggerOutputs(t *testing.T) {
	file := t.TempDir() + "/logs/vvideo.log"
	var c config.Config
	c.Log.Level, c.Log.Format, c.Log.Output, c.Log.File = slog.LevelWarn, "text", "file", file
	for range 2 { // a restart appends instead of truncating
		logger, closeLog, err := newLogger(c, nil, nil)
		if err != nil {
			t.Fatal(err)
		}
		logger.Info("hidden")
		logger.Warn("shown", "n", 1)
		if err = closeLog(); err != nil {
			t.Fatal(err)
		}
	}
	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	if got := string(data); strings.Count(got, "level=WARN msg=shown n=1\n") != 2 || strings.Contains(got, "hidden") {
		t.Fatalf("log file content:\n%s", got)
	}
	var stdout, stderr bytes.Buffer
	c.Log.Level, c.Log.Format, c.Log.Output, c.Log.File = slog.LevelDebug, "json", "stderr", ""
	logger, _, err := newLogger(c, &stdout, &stderr)
	if err != nil {
		t.Fatal(err)
	}
	logger.Debug("detail")
	if stdout.Len() != 0 || !strings.Contains(stderr.String(), `"level":"DEBUG","msg":"detail"`) {
		t.Fatalf("stdout %q, stderr %q", stdout.String(), stderr.String())
	}
}
