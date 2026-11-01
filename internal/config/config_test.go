package config

import (
	"crypto/sha256"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func envFor(t *testing.T) (string, []string) {
	t.Helper()
	d := t.TempDir()
	d, err := Canonical(d)
	if err != nil {
		t.Fatal(err)
	}
	return d, []string{"VVIDEO_AUTH__TOKEN=secret", `VVIDEO_MEDIA=[{"path":"` + filepath.Join(d, "media") + `"}]`}
}

// source reads no file; env holds KEY=value entries, later ones winning.
func source(dir string, env []string) Source {
	vars := map[string]string{}
	for _, entry := range env {
		k, v, _ := strings.Cut(entry, "=")
		vars[k] = v
	}
	lookup := func(k string) (string, bool) {
		v, ok := vars[k]
		return v, ok
	}
	return Source{WorkDir: dir, LookupEnv: lookup, Environ: env}
}

// explicit selects path as if it came from --config or CONFIG_FILE.
func explicit(src Source, path string) Source {
	src.Path, src.Explicit = path, true
	return src
}

func TestExplicitFileAndEmptyOverride(t *testing.T) {
	for _, v := range []string{"", "missing.yaml"} {
		t.Run("file"+v, func(t *testing.T) {
			d, e := envFor(t)
			if _, err := Load(explicit(source(d, e), v)); err == nil {
				t.Fatal("explicit invalid file accepted")
			}
		})
	}
	d, e := envFor(t)
	if _, err := Load(source(d, append(e, "VVIDEO_AUTH__TOKEN="))); err == nil {
		t.Fatal("empty token accepted")
	}
	c, err := Load(source(d, e))
	if err != nil {
		t.Fatal(err)
	}
	if c.Storage != filepath.Join(d, "data") || c.Database() != filepath.Join(d, "data/data.sqlite") || c.Scan.Parallel != 2 || c.Scan.ProbeParallel != 2 || c.Cache.MaxSize != 30_000_000_000 {
		t.Fatalf("wrong defaults: %s %d %d %d", c.Storage, c.Scan.Parallel, c.Scan.ProbeParallel, c.Cache.MaxSize)
	}
	if c.Address() != "0.0.0.0:8080" || c.Cache.Path != filepath.Join(d, "cache") {
		t.Fatalf("wrong default address %s or cache path %s", c.Address(), c.Cache.Path)
	}
}
func TestStrictYAML(t *testing.T) {
	for _, input := range []string{"auth: {token: supersecret}\nauth: {token: other}", "scan: {parallel: 2.5}", "scan: {parallel: '2'}", "scan: {probe_parallel: 2.5}", "auth: {token: 42}", "old: true", "cache: 3", "media: &x [{path: /a}]\nignore: *x", "auth: {token: !private supersecret}", "scan: {<<: {parallel: 2}}", "{}\n---\n{}", "[]", ""} {
		t.Run(input, func(t *testing.T) {
			d, e := envFor(t)
			p := filepath.Join(d, "bad.yaml")
			if err := os.WriteFile(p, []byte(input), 0o600); err != nil {
				t.Fatal(err)
			}
			_, err := Load(explicit(source(d, e), p))
			if err == nil {
				t.Fatal("invalid YAML accepted")
			}
			// The temporary path embeds the subtest name, so only the message is checked.
			if strings.Contains(strings.ReplaceAll(err.Error(), p, ""), "supersecret") {
				t.Fatal("credential leaked")
			}
		})
	}
}

func TestOversizedConfigurationRejected(t *testing.T) {
	d, e := envFor(t)
	p := filepath.Join(d, "large.yaml")
	// A valid leading document must not hide bytes beyond the reader limit.
	input := "{}\n#" + strings.Repeat("x", 1<<20)
	if err := os.WriteFile(p, []byte(input), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(explicit(source(d, e), p)); err == nil {
		t.Fatal("oversized configuration accepted")
	}
}

func TestCronTimezoneDaylightBoundaries(t *testing.T) {
	for _, tc := range []struct{ spec, after, want string }{
		{"CRON_TZ=America/New_York 30 2 * * *", "2026-03-08T06:59:00Z", "2026-03-09T06:30:00Z"},
		{"CRON_TZ=America/New_York 30 1 * * *", "2026-11-01T05:29:00Z", "2026-11-01T05:30:00Z"},
		{"CRON_TZ=America/New_York 30 1 * * *", "2026-11-01T05:30:00Z", "2026-11-01T06:30:00Z"},
	} {
		schedule, err := ParseCron(tc.spec)
		if err != nil {
			t.Fatal(err)
		}
		after, err := time.Parse(time.RFC3339, tc.after)
		if err != nil {
			t.Fatal(err)
		}
		if got := schedule.Next(after).UTC().Format(time.RFC3339); got != tc.want {
			t.Fatalf("%s after %s: %s, want %s", tc.spec, tc.after, got, tc.want)
		}
	}
}
func TestOriginsAndEnvironment(t *testing.T) {
	d, e := envFor(t)
	p := filepath.Join(d, "configs", "instance.yaml")
	os.MkdirAll(filepath.Dir(p), 0o700)
	os.WriteFile(p, []byte("storage: ../db\ncache: {path: ../thumbs, percent: 0}\nscan: {cron: '', probe_parallel: 6}\n"), 0o600)
	c, err := Load(explicit(source(d, e), "configs/instance.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	if c.Database() != filepath.Join(d, "db/data.sqlite") || c.Cache.Path != filepath.Join(d, "thumbs") || c.Cache.Percent != 0 || c.Scan.Cron != "" || c.Scan.ProbeParallel != 6 {
		t.Fatal("file origins or zero values lost")
	}
	c, err = Load(explicit(source(d, append(e, "VVIDEO_STORAGE=env", "VVIDEO_IGNORE=[]", "VVIDEO_SCAN__PROBE_PARALLEL=3")), p))
	if err != nil {
		t.Fatal(err)
	}
	if c.Storage != filepath.Join(d, "env") || len(c.Ignore) != 0 || c.Scan.ProbeParallel != 3 {
		t.Fatal("environment did not replace file")
	}
}
func TestInvalidValuesAndBoundaries(t *testing.T) {
	for _, v := range []string{"VVIDEO_SCAN__PARALLEL=0", "VVIDEO_SCAN__PARALLEL=-1", "VVIDEO_SCAN__PARALLEL=1.5", "VVIDEO_SCAN__PARALLEL=999999999999999999999", "VVIDEO_SCAN__PROBE_PARALLEL=0", "VVIDEO_SCAN__PROBE_PARALLEL=1.5", "VVIDEO_SCAN__PROBE_PARALLEL=1025", "VVIDEO_CACHE__THREADS=0", "VVIDEO_CACHE__PERCENT=100", "VVIDEO_CACHE__PERCENT=NaN", "VVIDEO_CACHE__PERCENT=[10]", "VVIDEO_CACHE__MAX_SIZE=0GB", "VVIDEO_CACHE__MAX_SIZE=99999999999999999GiB", "VVIDEO_CACHE__PATH=", "VVIDEO_SCAN__CRON=* * * * * *", "VVIDEO_SCAN__CRON=CRON_TZ=Not/AZone * * * * *", "VVIDEO_MEDIA=[]", "VVIDEO_MEDIA=[{\"path\":\"x\",\"other\":1}]", "VVIDEO_MEDIA={}", "VVIDEO_OLD=1"} {
		t.Run(v, func(t *testing.T) {
			d, e := envFor(t)
			if _, err := Load(source(d, append(e, v))); err == nil {
				t.Fatal("invalid value accepted")
			}
		})
	}
	d, e := envFor(t)
	for _, v := range []string{`VVIDEO_STORAGE=media/db`, `VVIDEO_CACHE__PATH=media/thumbs`, `VVIDEO_MEDIA=[{"path":"media"},{"path":"media/child"}]`, `VVIDEO_IGNORE=[{"path":"media2"}]`, `VVIDEO_IGNORE=[{"path":"media"}]`} {
		if _, err := Load(source(d, append(e, v))); err == nil {
			t.Errorf("boundary accepted: %s", v)
		}
	}
	os.MkdirAll(filepath.Join(d, "media"), 0o700)
	os.Symlink(filepath.Join(d, "media"), filepath.Join(d, "link"))
	if _, err := Load(source(d, append(e, "VVIDEO_STORAGE=link/db"))); err == nil {
		t.Fatal("symlink write overlap accepted")
	}
	c, err := Load(source(d, append(e, `VVIDEO_IGNORE=[{"path":"media/absent"}]`)))
	if err != nil {
		t.Fatal(err)
	}
	if !c.IsIgnored(filepath.Join(d, "media/absent/file.mp4")) || c.IsIgnored(filepath.Join(d, "media/absent2/file.mp4")) {
		t.Fatal("ignore component boundary wrong")
	}
}

func TestListenAndPort(t *testing.T) {
	for _, tc := range []struct {
		env  []string
		want string
	}{
		{[]string{"VVIDEO_SERVER__LISTEN=127.0.0.1", "VVIDEO_SERVER__PORT=9000"}, "127.0.0.1:9000"},
		{[]string{"VVIDEO_SERVER__LISTEN=::1"}, "[::1]:8080"},
		{[]string{"VVIDEO_SERVER__LISTEN=localhost"}, "localhost:8080"},
		{[]string{"VVIDEO_SERVER__LISTEN=fe80::1%en0"}, "[fe80::1%en0]:8080"},
	} {
		d, e := envFor(t)
		c, err := Load(source(d, append(e, tc.env...)))
		if err != nil {
			t.Fatal(err)
		}
		if c.Address() != tc.want {
			t.Errorf("%v: address %s, want %s", tc.env, c.Address(), tc.want)
		}
	}
	for _, v := range []string{"VVIDEO_SERVER__LISTEN=0.0.0.0:8080", "VVIDEO_SERVER__LISTEN=[::1]", "VVIDEO_SERVER__LISTEN=", "VVIDEO_SERVER__LISTEN= 127.0.0.1", "VVIDEO_SERVER__PORT=0", "VVIDEO_SERVER__PORT=65536", "VVIDEO_SERVER__PORT=http"} {
		d, e := envFor(t)
		if _, err := Load(source(d, append(e, v))); err == nil {
			t.Errorf("invalid listen setting accepted: %s", v)
		}
	}
	d, e := envFor(t)
	p := filepath.Join(d, "port.yaml")
	if err := os.WriteFile(p, []byte("server: {port: '8080'}\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(explicit(source(d, e), p)); err == nil || !strings.Contains(err.Error(), "line 1: server.port: must be an integer") {
		t.Fatalf("quoted port accepted or unexplained: %v", err)
	}
}

func TestStorageIsADirectory(t *testing.T) {
	d, e := envFor(t)
	file := filepath.Join(d, "data.sqlite")
	if err := os.WriteFile(file, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	_, err := Load(source(d, append(e, "VVIDEO_STORAGE=data.sqlite")))
	if err == nil || !strings.Contains(err.Error(), "must be a directory") {
		t.Fatalf("storage file accepted or unexplained: %v", err)
	}
	c, err := Load(source(d, append(e, "VVIDEO_STORAGE=state")))
	if err != nil {
		t.Fatal(err)
	}
	if c.Database() != filepath.Join(d, "state", DatabaseFile) || StageDir(c.Storage) != filepath.Join(d, "state", ".scan-tmp") {
		t.Fatalf("database %s or staging directory outside storage", c.Database())
	}
}

func TestFileSelectionPrecedence(t *testing.T) {
	d, err := Canonical(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	dir := filepath.Join(d, "configs")
	if err = os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	for name, token := range map[string]string{"config.local.yaml": "default-token", "environment.yaml": "environment-token", "selected.yaml": "selected-token"} {
		data := "auth: {token: " + token + "}\nmedia: [{path: ../media}]\nstorage: ../db\n"
		if err = os.WriteFile(filepath.Join(dir, name), []byte(data), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	for _, tc := range []struct {
		name, file string
		explicit   bool
		env        []string
		token      string
	}{
		{name: "default", file: "configs/config.local.yaml", token: "default-token"},
		{name: "relative explicit", file: "configs/selected.yaml", explicit: true, token: "selected-token"},
		{name: "absolute explicit", file: filepath.Join(dir, "selected.yaml"), explicit: true, token: "selected-token"},
		{name: "field variable beats file", file: "configs/selected.yaml", explicit: true, env: []string{"VVIDEO_AUTH__TOKEN=field-token"}, token: "field-token"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			src := source(d, tc.env)
			src.Path, src.Explicit = tc.file, tc.explicit
			c, err := Load(src)
			if err != nil {
				t.Fatal(err)
			}
			if c.Auth.TokenDigest != sha256.Sum256([]byte(tc.token)) {
				t.Fatal("wrong configuration precedence")
			}
			if c.Storage != filepath.Join(d, "db") || c.Media[0].Path != filepath.Join(d, "media") {
				t.Fatal("file paths did not resolve against the selected file's directory")
			}
		})
	}
	if _, err = Load(Source{WorkDir: d, Path: "configs/missing.yaml", Explicit: true}); err == nil {
		t.Fatal("missing explicit file accepted")
	}
	// A missing implicit file leaves the defaults and the environment.
	c, err := Load(Source{WorkDir: d, Path: "configs/missing.yaml", LookupEnv: source(d, []string{"VVIDEO_AUTH__TOKEN=t", `VVIDEO_MEDIA=[{"path":"media"}]`}).LookupEnv})
	if err != nil || c.Storage != filepath.Join(d, "data") {
		t.Fatalf("implicit missing file: %+v, %v", c, err)
	}
}

func TestErrorsNameTheProblem(t *testing.T) {
	d, e := envFor(t)
	bad := filepath.Join(d, "bad.yaml")
	if err := os.WriteFile(bad, []byte("auth: {token: supersecret}\ncache: {colour: blue}\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name string
		src  Source
		want []string
	}{
		{"missing file", explicit(source(d, e), "nope.yaml"), []string{"nope.yaml", "no such file"}},
		{"unknown field", explicit(source(d, e), bad), []string{bad, "line 2", `"cache.colour"`}},
		{"unknown variable", source(d, append(e, "VVIDEO_CACHE__COLOUR=blue")), []string{"unknown environment variable VVIDEO_CACHE__COLOUR"}},
		{"variable in the wrong case", source(d, append(e, "VVIDEO_auth__token=x")), []string{"unknown environment variable VVIDEO_auth__token"}},
		{"no file and no token", Source{WorkDir: d, Path: "configs/config.local.yaml"}, []string{"auth.token: required setting missing", filepath.Join(d, "configs/config.local.yaml"), "--config"}},
		{"port out of range", source(d, append(e, "VVIDEO_SERVER__PORT=0")), []string{"server.port: must be an integer between 1 and 65535"}},
		{"bad worker count", source(d, append(e, "VVIDEO_SCAN__PARALLEL=0")), []string{"scan.parallel: must be an integer between 1 and 1024"}},
		{"bad probe worker count", source(d, append(e, "VVIDEO_SCAN__PROBE_PARALLEL=-1")), []string{"scan.probe_parallel: "}},
		{"media overlap", source(d, append(e, "VVIDEO_STORAGE=media/db")), []string{"storage: ", "overlaps media root"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := Load(tc.src)
			if err == nil {
				t.Fatal("accepted")
			}
			for _, want := range tc.want {
				if !strings.Contains(err.Error(), want) {
					t.Errorf("error %q does not mention %q", err, want)
				}
			}
			if strings.Contains(err.Error(), "supersecret") {
				t.Fatal("credential leaked")
			}
		})
	}
}

func TestLogSettings(t *testing.T) {
	d, e := envFor(t)
	c, err := Load(source(d, e))
	if err != nil {
		t.Fatal(err)
	}
	if c.Log.Level != slog.LevelInfo || c.Log.Format != "json" || c.Log.Output != "stdout" || c.Log.File != "" {
		t.Fatalf("wrong log defaults: %+v", c.Log)
	}
	p := filepath.Join(d, "configs", "log.yaml")
	os.MkdirAll(filepath.Dir(p), 0o700)
	os.WriteFile(p, []byte("log: {level: debug, format: text, output: file, file: ../logs/vvideo.log}\n"), 0o600)
	if c, err = Load(explicit(source(d, e), p)); err != nil {
		t.Fatal(err)
	}
	if c.Log.Level != slog.LevelDebug || c.Log.Format != "text" || c.Log.File != filepath.Join(d, "logs/vvideo.log") {
		t.Fatalf("log file settings lost: %+v", c.Log)
	}
	if c, err = Load(explicit(source(d, append(e, "VVIDEO_LOG__OUTPUT=stderr", "VVIDEO_LOG__FILE=", "VVIDEO_LOG__LEVEL=warn")), p)); err != nil {
		t.Fatal(err)
	}
	if c.Log.Output != "stderr" || c.Log.File != "" || c.Log.Level != slog.LevelWarn {
		t.Fatalf("environment did not replace log settings: %+v", c.Log)
	}
	os.MkdirAll(filepath.Join(d, "dir.log"), 0o700)
	for _, tc := range []struct{ env, want string }{
		{"VVIDEO_LOG__LEVEL=INFO", "log.level"},
		{"VVIDEO_LOG__LEVEL=trace", "log.level"},
		{"VVIDEO_LOG__FORMAT=logfmt", "log.format"},
		{"VVIDEO_LOG__OUTPUT=syslog", "log.output"},
		{"VVIDEO_LOG__OUTPUT=file", "log.file: must be set"},
		{"VVIDEO_LOG__FILE=vvideo.log", "log.file: only used when log.output is file"},
	} {
		if _, err := Load(source(d, append(e, tc.env))); err == nil || !strings.Contains(err.Error(), tc.want) {
			t.Errorf("%s: %v, want an error naming %q", tc.env, err, tc.want)
		}
	}
	for _, file := range []string{"media/vvideo.log", "dir.log"} {
		if _, err := Load(source(d, append(e, "VVIDEO_LOG__OUTPUT=file", "VVIDEO_LOG__FILE="+file))); err == nil || !strings.Contains(err.Error(), "log.file") {
			t.Errorf("log file %s accepted or unexplained: %v", file, err)
		}
	}
	bad := filepath.Join(d, "level.yaml")
	os.WriteFile(bad, []byte("log: {level: 1}\n"), 0o600)
	if _, err := Load(explicit(source(d, e), bad)); err == nil || !strings.Contains(err.Error(), "log.level: must be a string") {
		t.Fatalf("numeric level accepted or unexplained: %v", err)
	}
}

func TestExampleConfigLoads(t *testing.T) {
	d, err := Canonical(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	example, err := filepath.Abs("../../configs/config.example.yaml")
	if err != nil {
		t.Fatal(err)
	}
	c, err := Load(explicit(source(d, []string{"VVIDEO_AUTH__TOKEN=example"}), example))
	if err != nil {
		t.Fatal(err)
	}
	repo, err := Canonical(filepath.Dir(filepath.Dir(example)))
	if err != nil {
		t.Fatal(err)
	}
	if c.Address() != "0.0.0.0:8080" || c.Database() != filepath.Join(repo, "data", DatabaseFile) || c.Cache.Path != filepath.Join(repo, "cache") {
		t.Fatalf("example resolves to %s, %s and %s", c.Address(), c.Database(), c.Cache.Path)
	}
	if c.Log.Level != slog.LevelInfo || c.Log.Format != "json" || c.Log.Output != "stdout" {
		t.Fatalf("example log settings %+v differ from the defaults", c.Log)
	}
}

func TestValidateDoesNotModify(t *testing.T) {
	d, e := envFor(t)
	c, err := Load(source(d, e))
	if err != nil {
		t.Fatal(err)
	}
	if err = c.Validate(); err != nil {
		t.Fatalf("loaded configuration fails validation: %v", err)
	}
	for _, tc := range []struct {
		name   string
		change func(*Config)
		want   string
	}{
		{"port", func(c *Config) { c.Server.Port = 70000 }, "server.port: must be an integer between 1 and 65535"},
		{"token", func(c *Config) { c.Auth.TokenDigest = [sha256.Size]byte{} }, "auth.token: required setting missing"},
		{"workers", func(c *Config) { c.Cache.Threads = 0 }, "cache.threads: must be an integer between 1 and 1024"},
		{"percent", func(c *Config) { c.Cache.Percent = 100 }, "cache.percent: must be a number in [0, 100)"},
		{"relative storage", func(c *Config) { c.Storage = "data" }, "storage: must be an absolute path"},
		{"log output", func(c *Config) { c.Log.Output = "syslog" }, "log.output: must be stdout, stderr or file"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			changed := c
			tc.change(&changed)
			before := changed
			err := changed.Validate()
			if err == nil || err.Error() != tc.want {
				t.Fatalf("got %v, want %q", err, tc.want)
			}
			if changed.Server != before.Server || changed.Auth != before.Auth || changed.Storage != before.Storage || changed.Cache != before.Cache || changed.Log != before.Log {
				t.Fatal("Validate modified the configuration")
			}
		})
	}
}

func TestTokenFitsAuthorizationHeader(t *testing.T) {
	dir, env := envFor(t)
	for _, token := range []string{"line\nbreak", "\rheader", "embedded space", "令牌", strings.Repeat("a", 4097)} {
		_, err := Load(source(dir, append(env, "VVIDEO_AUTH__TOKEN="+token)))
		if err == nil || !strings.Contains(err.Error(), "auth.token:") || strings.Contains(err.Error(), token) {
			t.Fatal("invalid token accepted or exposed")
		}
	}
	if _, err := Load(source(dir, append(env, "VVIDEO_AUTH__TOKEN="+strings.Repeat("a", 4096)))); err != nil {
		t.Fatal(err)
	}
}
