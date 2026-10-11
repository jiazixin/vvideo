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
			if _, err := Load(d, append(e, "CONFIG_FILE="+v)); err == nil {
				t.Fatal("explicit invalid file accepted")
			}
		})
	}
	d, e := envFor(t)
	if _, err := Load(d, append(e, "VVIDEO_AUTH__TOKEN=")); err == nil {
		t.Fatal("empty token accepted")
	}
	c, err := Load(d, e)
	if err != nil {
		t.Fatal(err)
	}
	if c.Storage != filepath.Join(d, "data/data.sqlite") || c.Scan.Parallel != 2 || c.Cache.MaxSize != 30_000_000_000 {
		t.Fatalf("wrong defaults: %s %d %d", c.Storage, c.Scan.Parallel, c.Cache.MaxSize)
	}
}
func TestStrictYAML(t *testing.T) {
	for _, input := range []string{"auth: {token: supersecret}\nauth: {token: other}", "scan: {parallel: 2.5}", "scan: {parallel: '2'}", "auth: {token: 42}", "old: true", "cache: 3", "media: &x [{path: /a}]\nignore: *x", "auth: {token: !private supersecret}", "scan: {<<: {parallel: 2}}", "{}\n---\n{}", "[]", ""} {
		t.Run(input, func(t *testing.T) {
			d, e := envFor(t)
			p := filepath.Join(d, "bad.yaml")
			if err := os.WriteFile(p, []byte(input), 0600); err != nil {
				t.Fatal(err)
			}
			_, err := Load(d, append(e, "CONFIG_FILE="+p))
			if err == nil {
				t.Fatal("invalid YAML accepted")
			}
			if strings.Contains(err.Error(), "supersecret") {
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
	if _, err := Load(d, append(e, "CONFIG_FILE="+p)); err == nil {
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
	os.WriteFile(p, []byte("storage: ../db/index.sqlite\ncache: {path: ../thumbs, percent: 0}\nscan: {cron: ''}\n"), 0600)
	c, err := Load(d, append(e, "CONFIG_FILE=configs/instance.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	if c.Storage != filepath.Join(d, "db/index.sqlite") || c.Cache.Path != filepath.Join(d, "thumbs") || c.Cache.Percent != 0 || c.Scan.Cron != "" {
		t.Fatal("file origins or zero values lost")
	}
	c, err = Load(d, append(e, "CONFIG_FILE="+p, "VVIDEO_STORAGE=env.sqlite", "VVIDEO_IGNORE=[]"))
	if err != nil {
		t.Fatal(err)
	}
	if c.Storage != filepath.Join(d, "env.sqlite") || len(c.Ignore) != 0 {
		t.Fatal("environment did not replace file")
	}
}
func TestInvalidValuesAndBoundaries(t *testing.T) {
	for _, v := range []string{"VVIDEO_SCAN__PARALLEL=0", "VVIDEO_SCAN__PARALLEL=-1", "VVIDEO_SCAN__PARALLEL=1.5", "VVIDEO_SCAN__PARALLEL=999999999999999999999", "VVIDEO_CACHE__THREADS=0", "VVIDEO_CACHE__PERCENT=100", "VVIDEO_CACHE__PERCENT=NaN", "VVIDEO_CACHE__PERCENT=[10]", "VVIDEO_CACHE__MAX_SIZE=0GB", "VVIDEO_CACHE__MAX_SIZE=99999999999999999GiB", "VVIDEO_CACHE__PATH=", "VVIDEO_SCAN__CRON=* * * * * *", "VVIDEO_SCAN__CRON=CRON_TZ=Not/AZone * * * * *", "VVIDEO_MEDIA=[]", "VVIDEO_MEDIA=[{\"path\":\"x\",\"other\":1}]", "VVIDEO_MEDIA={}", "VVIDEO_OLD=1"} {
		t.Run(v, func(t *testing.T) {
			d, e := envFor(t)
			if _, err := Load(d, append(e, v)); err == nil {
				t.Fatal("invalid value accepted")
			}
		})
	}
	d, e := envFor(t)
	for _, v := range []string{`VVIDEO_STORAGE=media/db.sqlite`, `VVIDEO_CACHE__PATH=media/thumbs`, `VVIDEO_MEDIA=[{"path":"media"},{"path":"media/child"}]`, `VVIDEO_IGNORE=[{"path":"media2"}]`, `VVIDEO_IGNORE=[{"path":"media"}]`} {
		if _, err := Load(d, append(e, v)); err == nil {
			t.Errorf("boundary accepted: %s", v)
		}
	}
	os.MkdirAll(filepath.Join(d, "media"), 0700)
	os.Symlink(filepath.Join(d, "media"), filepath.Join(d, "link"))
	if _, err := Load(d, append(e, "VVIDEO_STORAGE=link/db.sqlite")); err == nil {
		t.Fatal("symlink write overlap accepted")
	}
	c, err := Load(d, append(e, `VVIDEO_IGNORE=[{"path":"media/absent"}]`))
	if err != nil {
		t.Fatal(err)
	}
	if !c.IsIgnored(filepath.Join(d, "media/absent/file.mp4")) || c.IsIgnored(filepath.Join(d, "media/absent2/file.mp4")) {
		t.Fatal("ignore component boundary wrong")
	}
}
