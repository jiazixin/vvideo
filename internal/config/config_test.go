package config

import (
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
func TestExplicitFileAndEmptyOverride(t *testing.T) {
	for _, v := range []string{"", "missing.yaml"} {
		t.Run("file"+v, func(t *testing.T) {
			d, e := envFor(t)
			if _, err := Load(Source{WorkDir: d, Env: append(e, "CONFIG_FILE="+v)}); err == nil {
				t.Fatal("explicit invalid file accepted")
			}
		})
	}
	d, e := envFor(t)
	if _, err := Load(Source{WorkDir: d, Env: append(e, "VVIDEO_AUTH__TOKEN=")}); err == nil {
		t.Fatal("empty token accepted")
	}
	c, err := Load(Source{WorkDir: d, Env: e})
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
			if err := os.WriteFile(p, []byte(input), 0600); err != nil {
				t.Fatal(err)
			}
			_, err := Load(Source{WorkDir: d, Env: append(e, "CONFIG_FILE="+p)})
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
	if err := os.WriteFile(p, []byte(input), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(Source{WorkDir: d, Env: append(e, "CONFIG_FILE="+p)}); err == nil {
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
	os.MkdirAll(filepath.Dir(p), 0700)
	os.WriteFile(p, []byte("storage: ../db\ncache: {path: ../thumbs, percent: 0}\nscan: {cron: '', probe_parallel: 6}\n"), 0600)
	c, err := Load(Source{WorkDir: d, Env: append(e, "CONFIG_FILE=configs/instance.yaml")})
	if err != nil {
		t.Fatal(err)
	}
	if c.Database() != filepath.Join(d, "db/data.sqlite") || c.Cache.Path != filepath.Join(d, "thumbs") || c.Cache.Percent != 0 || c.Scan.Cron != "" || c.Scan.ProbeParallel != 6 {
		t.Fatal("file origins or zero values lost")
	}
	c, err = Load(Source{WorkDir: d, Env: append(e, "CONFIG_FILE="+p, "VVIDEO_STORAGE=env", "VVIDEO_IGNORE=[]", "VVIDEO_SCAN__PROBE_PARALLEL=3")})
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
			if _, err := Load(Source{WorkDir: d, Env: append(e, v)}); err == nil {
				t.Fatal("invalid value accepted")
			}
		})
	}
	d, e := envFor(t)
	for _, v := range []string{`VVIDEO_STORAGE=media/db`, `VVIDEO_CACHE__PATH=media/thumbs`, `VVIDEO_MEDIA=[{"path":"media"},{"path":"media/child"}]`, `VVIDEO_IGNORE=[{"path":"media2"}]`, `VVIDEO_IGNORE=[{"path":"media"}]`} {
		if _, err := Load(Source{WorkDir: d, Env: append(e, v)}); err == nil {
			t.Errorf("boundary accepted: %s", v)
		}
	}
	os.MkdirAll(filepath.Join(d, "media"), 0700)
	os.Symlink(filepath.Join(d, "media"), filepath.Join(d, "link"))
	if _, err := Load(Source{WorkDir: d, Env: append(e, "VVIDEO_STORAGE=link/db")}); err == nil {
		t.Fatal("symlink write overlap accepted")
	}
	c, err := Load(Source{WorkDir: d, Env: append(e, `VVIDEO_IGNORE=[{"path":"media/absent"}]`)})
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
		c, err := Load(Source{WorkDir: d, Env: append(e, tc.env...)})
		if err != nil {
			t.Fatal(err)
		}
		if c.Address() != tc.want {
			t.Errorf("%v: address %s, want %s", tc.env, c.Address(), tc.want)
		}
	}
	for _, v := range []string{"VVIDEO_SERVER__LISTEN=0.0.0.0:8080", "VVIDEO_SERVER__LISTEN=[::1]", "VVIDEO_SERVER__LISTEN=", "VVIDEO_SERVER__LISTEN= 127.0.0.1", "VVIDEO_SERVER__PORT=0", "VVIDEO_SERVER__PORT=65536", "VVIDEO_SERVER__PORT=http"} {
		d, e := envFor(t)
		if _, err := Load(Source{WorkDir: d, Env: append(e, v)}); err == nil {
			t.Errorf("invalid listen setting accepted: %s", v)
		}
	}
	d, e := envFor(t)
	p := filepath.Join(d, "port.yaml")
	if err := os.WriteFile(p, []byte("server: {port: '8080'}\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(Source{WorkDir: d, Env: e, File: p}); err == nil || !strings.Contains(err.Error(), "server.port must be an integer") {
		t.Fatalf("quoted port accepted or unexplained: %v", err)
	}
}

func TestStorageIsADirectory(t *testing.T) {
	d, e := envFor(t)
	file := filepath.Join(d, "data.sqlite")
	if err := os.WriteFile(file, nil, 0600); err != nil {
		t.Fatal(err)
	}
	_, err := Load(Source{WorkDir: d, Env: append(e, "VVIDEO_STORAGE=data.sqlite")})
	if err == nil || !strings.Contains(err.Error(), "must be a directory") {
		t.Fatalf("storage file accepted or unexplained: %v", err)
	}
	c, err := Load(Source{WorkDir: d, Env: append(e, "VVIDEO_STORAGE=state")})
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
	if err = os.MkdirAll(dir, 0700); err != nil {
		t.Fatal(err)
	}
	for name, token := range map[string]string{"config.local.yaml": "default-token", "environment.yaml": "environment-token", "selected.yaml": "selected-token"} {
		data := "auth: {token: " + token + "}\nmedia: [{path: ../media}]\nstorage: ../db\n"
		if err = os.WriteFile(filepath.Join(dir, name), []byte(data), 0600); err != nil {
			t.Fatal(err)
		}
	}
	for _, tc := range []struct {
		name, file string
		env        []string
		token      string
	}{
		{name: "default", token: "default-token"},
		{name: "environment", env: []string{"CONFIG_FILE=configs/environment.yaml"}, token: "environment-token"},
		{name: "relative flag", file: "configs/selected.yaml", token: "selected-token"},
		{name: "absolute flag", file: filepath.Join(dir, "selected.yaml"), token: "selected-token"},
		{name: "flag beats environment", file: "configs/selected.yaml", env: []string{"CONFIG_FILE=configs/environment.yaml"}, token: "selected-token"},
		{name: "flag beats invalid environment", file: "configs/selected.yaml", env: []string{"CONFIG_FILE="}, token: "selected-token"},
		{name: "field variable beats file", file: "configs/selected.yaml", env: []string{"VVIDEO_AUTH__TOKEN=field-token"}, token: "field-token"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			c, err := Load(Source{WorkDir: d, Env: tc.env, File: tc.file})
			if err != nil {
				t.Fatal(err)
			}
			if c.Auth.Token != tc.token {
				t.Fatal("wrong configuration precedence")
			}
			if c.Storage != filepath.Join(d, "db") || c.Media[0].Path != filepath.Join(d, "media") {
				t.Fatal("file paths did not resolve against the selected file's directory")
			}
		})
	}
	if _, err = Load(Source{WorkDir: d, File: "configs/missing.yaml"}); err == nil {
		t.Fatal("missing explicit file accepted")
	}
}

func TestErrorsNameTheProblem(t *testing.T) {
	d, e := envFor(t)
	bad := filepath.Join(d, "bad.yaml")
	if err := os.WriteFile(bad, []byte("auth: {token: supersecret}\ncache: {colour: blue}\n"), 0600); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name string
		src  Source
		want []string
	}{
		{"missing file", Source{WorkDir: d, Env: e, File: "nope.yaml"}, []string{"nope.yaml", "no such file"}},
		{"unknown field", Source{WorkDir: d, Env: e, File: bad}, []string{bad, "line 2", `"cache.colour"`}},
		{"unknown variable", Source{WorkDir: d, Env: append(e, "VVIDEO_CACHE__COLOUR=blue")}, []string{"VVIDEO_CACHE__COLOUR"}},
		{"no file and no token", Source{WorkDir: d}, []string{"auth.token", DefaultFile, "--config"}},
		{"bad worker count", Source{WorkDir: d, Env: append(e, "VVIDEO_SCAN__PARALLEL=0")}, []string{"scan.parallel"}},
		{"bad probe worker count", Source{WorkDir: d, Env: append(e, "VVIDEO_SCAN__PROBE_PARALLEL=-1")}, []string{"scan.probe_parallel"}},
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

func TestExampleConfigLoads(t *testing.T) {
	d, err := Canonical(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	example, err := filepath.Abs("../../configs/config.example.yaml")
	if err != nil {
		t.Fatal(err)
	}
	c, err := Load(Source{WorkDir: d, Env: []string{"VVIDEO_AUTH__TOKEN=example"}, File: example})
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
}
