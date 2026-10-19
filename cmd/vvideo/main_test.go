package main

import (
	"bytes"
	"strings"
	"testing"
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
