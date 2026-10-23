package main

import (
	"bytes"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"vvideo/internal/config"
)

func TestRunUsageErrors(t *testing.T) {
	for _, args := range [][]string{{"--bogus"}, {"-c"}, {"--config="}, {"config.yaml"}} {
		t.Run(strings.Join(args, " "), func(t *testing.T) {
			var stdout, stderr bytes.Buffer
			if code := run(args, &stdout, &stderr); code != exitUsage {
				t.Fatalf("exit %d, want %d", code, exitUsage)
			}
			if stdout.Len() != 0 || !strings.HasPrefix(stderr.String(), "vvideo: ") || !strings.HasSuffix(stderr.String(), "Run 'vvideo --help' for usage.\n") {
				t.Fatalf("stdout %q, stderr %q", stdout.String(), stderr.String())
			}
		})
	}
}

func TestRunHelpAndVersionSkipConfiguration(t *testing.T) {
	t.Chdir(t.TempDir())
	t.Setenv("CONFIG_FILE", "missing.yaml")
	for _, tc := range []struct {
		args []string
		want string
	}{
		{[]string{"-h"}, usage},
		{[]string{"--help"}, usage},
		{[]string{"-c", "missing.yaml", "--help"}, usage},
		{[]string{"--version"}, "vvideo " + currentBuild().String() + "\n"},
	} {
		t.Run(strings.Join(tc.args, " "), func(t *testing.T) {
			var stdout, stderr bytes.Buffer
			if code := run(tc.args, &stdout, &stderr); code != exitOK {
				t.Fatalf("exit %d, stderr %q", code, stderr.String())
			}
			if stdout.String() != tc.want || stderr.Len() != 0 {
				t.Fatalf("stdout %q, stderr %q", stdout.String(), stderr.String())
			}
		})
	}
}

func TestRunConfigSelection(t *testing.T) {
	t.Chdir(t.TempDir())
	dir := t.TempDir()
	flagFile, envFile := filepath.Join(dir, "flag.yaml"), filepath.Join(dir, "env.yaml")
	t.Setenv("CONFIG_FILE", envFile)
	for _, tc := range []struct {
		name          string
		args          []string
		want, notWant string
	}{
		{"environment", []string{}, envFile, flagFile},
		{"flag beats environment", []string{"-c", flagFile}, flagFile, envFile},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var stdout, stderr bytes.Buffer
			if code := run(tc.args, &stdout, &stderr); code != exitFailure {
				t.Fatalf("exit %d, want %d", code, exitFailure)
			}
			got := stderr.String()
			if stdout.Len() != 0 || !strings.HasPrefix(got, "vvideo: ") || !strings.Contains(got, tc.want) || strings.Contains(got, tc.notWant) {
				t.Fatalf("stdout %q, stderr %q", stdout.String(), got)
			}
		})
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
