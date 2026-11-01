package main

import (
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
		{[]string{"-c", "a.yaml", "--config", "a.yaml"}, options{configFile: "a.yaml"}},
		{[]string{"-h"}, options{help: true}},
		{[]string{"--help"}, options{help: true}},
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

func TestParseArgsErrors(t *testing.T) {
	for _, tc := range []struct {
		args []string
		want string
	}{
		{[]string{"-c"}, "flag needs an argument"},
		{[]string{"--config="}, "must not be empty"},
		{[]string{"-c", " "}, "must not be empty"},
		{[]string{"-c", "a.yaml", "--config", "b.yaml"}, `different files given for -c/--config: "a.yaml" and "b.yaml"`},
		{[]string{"--bogus"}, "not defined: -bogus"},
		{[]string{"-v"}, "not defined: -v"},
		{[]string{"config.yaml"}, `unexpected argument "config.yaml"`},
		{[]string{"--", "config.yaml"}, `unexpected argument "config.yaml"`},
	} {
		t.Run(strings.Join(tc.args, " "), func(t *testing.T) {
			_, err := parseArgs(tc.args)
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("got %v, want %q", err, tc.want)
			}
		})
	}
}

func TestConfigPath(t *testing.T) {
	env := func(vars map[string]string) func(string) (string, bool) {
		return func(k string) (string, bool) {
			v, ok := vars[k]
			return v, ok
		}
	}
	for _, tc := range []struct {
		name     string
		flag     string
		vars     map[string]string
		path     string
		explicit bool
		err      string
	}{
		{name: "default", path: defaultConfigFile},
		{name: "environment", vars: map[string]string{"CONFIG_FILE": "env.yaml"}, path: "env.yaml", explicit: true},
		{name: "flag beats environment", flag: "flag.yaml", vars: map[string]string{"CONFIG_FILE": "env.yaml"}, path: "flag.yaml", explicit: true},
		{name: "flag beats empty environment", flag: "flag.yaml", vars: map[string]string{"CONFIG_FILE": ""}, path: "flag.yaml", explicit: true},
		{name: "empty environment", vars: map[string]string{"CONFIG_FILE": ""}, err: "CONFIG_FILE is set but empty"},
		{name: "blank environment", vars: map[string]string{"CONFIG_FILE": " \t"}, err: "CONFIG_FILE is set but empty"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			path, explicit, err := options{configFile: tc.flag}.configPath(env(tc.vars))
			if tc.err != "" {
				if err == nil || err.Error() != tc.err {
					t.Fatalf("got %q, %v, %v; want error %q", path, explicit, err, tc.err)
				}
				return
			}
			if err != nil || path != tc.path || explicit != tc.explicit {
				t.Fatalf("got %q, %v, %v; want %q, %v", path, explicit, err, tc.path, tc.explicit)
			}
		})
	}
}
