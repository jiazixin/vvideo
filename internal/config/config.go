// Package config implements the strict, restart-only configuration contract.
package config

import (
	"bytes"
	"encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"

	"github.com/robfig/cron/v3"
	"go.yaml.in/yaml/v3"
)

type Path struct {
	Path string `yaml:"path" json:"path"`
}
type Config struct {
	Server        struct{ Listen string }
	Auth          struct{ Token string }
	Storage       string
	Media, Ignore []Path
	Scan          struct {
		Cron     string
		Parallel int
	}
	Cache struct {
		Path              string
		MaxSize           int64
		Parallel, Threads int
		Percent           float64
	}
}

var fields = map[string]string{
	"server.listen": "string", "auth.token": "string", "storage": "string",
	"media": "paths", "ignore": "paths", "scan.cron": "string", "scan.parallel": "int",
	"cache.path": "string", "cache.max_size": "string", "cache.parallel": "int", "cache.threads": "int", "cache.percent": "number",
}

// Load takes an explicit working directory and environment so tests never read
// credentials or change process-wide configuration. Errors never include values.
func Load(cwd string, env []string) (Config, error) {
	var c Config
	abs, err := filepath.Abs(cwd)
	if err != nil {
		return c, err
	}
	cwd = abs
	vals := map[string]string{"server.listen": "0.0.0.0:8080", "storage": "data/data.sqlite", "scan.cron": "*/30 * * * *", "scan.parallel": "2", "cache.path": ".cache/thumbnails", "cache.max_size": "30GB", "cache.parallel": "2", "cache.threads": "2", "cache.percent": "10", "ignore": "[]"}
	origins := map[string]string{}
	e := map[string]string{}
	for _, item := range env {
		k, v, ok := strings.Cut(item, "=")
		if ok {
			e[k] = v
		}
	}
	p, explicit := e["CONFIG_FILE"]
	if explicit && p == "" {
		return c, errors.New("CONFIG_FILE must not be empty")
	}
	if !explicit {
		p = "configs/config.local.yaml"
	}
	if !filepath.IsAbs(p) {
		p = filepath.Join(cwd, p)
	}
	f, err := os.Open(p)
	if err != nil {
		if explicit || !errors.Is(err, os.ErrNotExist) {
			return c, errors.New("configuration file cannot be read")
		}
	} else {
		defer f.Close()
		data, readErr := io.ReadAll(io.LimitReader(f, (1<<20)+1))
		if readErr != nil {
			return c, errors.New("configuration file cannot be read")
		}
		if len(data) > 1<<20 {
			return c, errors.New("configuration exceeds 1 MiB")
		}
		dec := yaml.NewDecoder(bytes.NewReader(data))
		var doc yaml.Node
		if err = dec.Decode(&doc); err != nil {
			return c, errors.New("configuration is not a YAML mapping document")
		}
		var extra yaml.Node
		if err = dec.Decode(&extra); !errors.Is(err, io.EOF) {
			return c, errors.New("configuration must contain one YAML document")
		}
		if len(doc.Content) != 1 || doc.Content[0].Kind != yaml.MappingNode {
			return c, errors.New("configuration must be a mapping")
		}
		if err = readMap(doc.Content[0], "", vals, origins, filepath.Dir(p), 0); err != nil {
			return c, err
		}
	}
	for k, v := range e {
		if !strings.HasPrefix(k, "VVIDEO_") {
			continue
		}
		name := strings.ToLower(strings.ReplaceAll(strings.TrimPrefix(k, "VVIDEO_"), "__", "."))
		if _, ok := fields[name]; !ok {
			return c, errors.New("unknown VVIDEO configuration variable")
		}
		vals[name] = v
		origins[name] = cwd
	}
	pathValue := func(k string) (string, error) {
		v := vals[k]
		if strings.TrimSpace(v) == "" {
			return "", fmt.Errorf("%s must not be empty", k)
		}
		base := origins[k]
		if base == "" {
			base = cwd
		}
		if !filepath.IsAbs(v) {
			v = filepath.Join(base, v)
		}
		return Canonical(v)
	}
	c.Server.Listen = vals["server.listen"]
	_, port, err := net.SplitHostPort(c.Server.Listen)
	if err != nil {
		return c, errors.New("server.listen must be host:port")
	}
	pn, err := strconv.Atoi(port)
	if err != nil || pn < 1 || pn > 65535 {
		return c, errors.New("invalid server.listen port")
	}
	c.Auth.Token = vals["auth.token"]
	if strings.TrimSpace(c.Auth.Token) == "" {
		return c, errors.New("auth.token is required")
	}
	if c.Storage, err = pathValue("storage"); err != nil {
		return c, err
	}
	if c.Cache.Path, err = pathValue("cache.path"); err != nil {
		return c, err
	}
	for k, target := range map[string]*int{"scan.parallel": &c.Scan.Parallel, "cache.parallel": &c.Cache.Parallel, "cache.threads": &c.Cache.Threads} {
		*target, err = strconv.Atoi(vals[k])
		if err != nil || *target <= 0 || *target > 1024 {
			return c, fmt.Errorf("%s must be a positive integer no greater than 1024", k)
		}
	}
	c.Cache.Percent, err = strconv.ParseFloat(vals["cache.percent"], 64)
	if err != nil || math.IsNaN(c.Cache.Percent) || math.IsInf(c.Cache.Percent, 0) || c.Cache.Percent < 0 || c.Cache.Percent >= 100 {
		return c, errors.New("cache.percent must be finite in [0,100)")
	}
	c.Cache.MaxSize, err = ParseSize(vals["cache.max_size"])
	if err != nil {
		return c, err
	}
	c.Scan.Cron = vals["scan.cron"]
	if c.Scan.Cron != "" {
		if _, err = ParseCron(c.Scan.Cron); err != nil {
			return c, errors.New("scan.cron must be a valid five-field cron with an optional CRON_TZ timezone")
		}
	}
	for k, target := range map[string]*[]Path{"media": &c.Media, "ignore": &c.Ignore} {
		if err = json.Unmarshal([]byte(vals[k]), target, json.RejectUnknownMembers(true)); err != nil {
			return c, fmt.Errorf("%s must be a JSON array of path objects", k)
		}
		if !strings.HasPrefix(strings.TrimSpace(vals[k]), "[") {
			return c, fmt.Errorf("%s must be an array", k)
		}
		for i := range *target {
			orig := (*target)[i].Path
			if strings.TrimSpace(orig) == "" {
				return c, fmt.Errorf("%s path must not be empty", k)
			}
			base := origins[k]
			if base == "" {
				base = cwd
			}
			if !filepath.IsAbs(orig) {
				orig = filepath.Join(base, orig)
			}
			(*target)[i].Path, err = Canonical(orig)
			if err != nil {
				return c, fmt.Errorf("%s path cannot be resolved", k)
			}
		}
	}
	if len(c.Media) == 0 {
		return c, errors.New("media must contain at least one root")
	}
	if Contains(c.Cache.Path, c.Storage) || Contains(c.Storage, c.Cache.Path) {
		return c, errors.New("storage and cache paths must be separate")
	}
	stage := filepath.Join(filepath.Dir(c.Storage), ".scan-tmp")
	for i, r := range c.Media {
		for _, s := range c.Media[:i] {
			if Contains(r.Path, s.Path) || Contains(s.Path, r.Path) {
				return c, errors.New("media roots must not overlap")
			}
		}
		for _, w := range []string{c.Storage, c.Cache.Path, stage} {
			if Contains(r.Path, w) || Contains(w, r.Path) {
				return c, errors.New("writable paths must not overlap media roots")
			}
		}
	}
	for _, x := range c.Ignore {
		valid := false
		for _, r := range c.Media {
			if x.Path != r.Path && Contains(r.Path, x.Path) {
				valid = true
				break
			}
		}
		if !valid {
			return c, errors.New("ignore path must be strictly inside a media root")
		}
	}
	return c, nil
}

func readMap(n *yaml.Node, prefix string, vals, origins map[string]string, base string, depth int) error {
	if depth > 8 || n.Kind != yaml.MappingNode || n.Tag != "!!map" {
		return fmt.Errorf("invalid mapping at line %d", n.Line)
	}
	seen := map[string]bool{}
	for i := 0; i < len(n.Content); i += 2 {
		k, v := n.Content[i], n.Content[i+1]
		if k.Tag != "!!str" || seen[k.Value] || k.Value == "<<" {
			return fmt.Errorf("invalid or duplicate key at line %d", k.Line)
		}
		seen[k.Value] = true
		name := k.Value
		if prefix != "" {
			name = prefix + "." + name
		}
		kind, ok := fields[name]
		if !ok {
			if prefix == "" && (name == "server" || name == "auth" || name == "scan" || name == "cache") {
				if err := readMap(v, name, vals, origins, base, depth+1); err != nil {
					return err
				}
				continue
			}
			return fmt.Errorf("unknown configuration field at line %d", k.Line)
		}
		if v.Kind == yaml.AliasNode || v.Anchor != "" {
			return fmt.Errorf("aliases and anchors are not allowed at line %d", v.Line)
		}
		switch kind {
		case "paths":
			if v.Kind != yaml.SequenceNode || v.Tag != "!!seq" {
				return fmt.Errorf("%s must be a list at line %d", name, v.Line)
			}
			paths := []Path{}
			for _, o := range v.Content {
				if o.Kind != yaml.MappingNode || o.Tag != "!!map" || o.Anchor != "" || len(o.Content) != 2 || o.Content[0].Value != "path" || o.Content[0].Tag != "!!str" || o.Content[1].Kind != yaml.ScalarNode || o.Content[1].Tag != "!!str" {
					return fmt.Errorf("invalid %s path object at line %d", name, o.Line)
				}
				paths = append(paths, Path{Path: o.Content[1].Value})
			}
			b, err := json.Marshal(paths)
			if err != nil {
				return err
			}
			vals[name] = string(b)
		default:
			tag := "!!str"
			if kind == "int" {
				tag = "!!int"
			}
			valid := v.Kind == yaml.ScalarNode && v.Tag == tag
			if kind == "number" {
				valid = v.Kind == yaml.ScalarNode && (v.Tag == "!!int" || v.Tag == "!!float")
			}
			if !valid {
				return fmt.Errorf("invalid %s type at line %d", name, v.Line)
			}
			vals[name] = v.Value
		}
		origins[name] = base
	}
	return nil
}

func ParseCron(s string) (cron.Schedule, error) {
	return cron.NewParser(cron.Minute | cron.Hour | cron.Dom | cron.Month | cron.Dow).Parse(s)
}

var capacity = regexp.MustCompile(`^([0-9]+(?:\.[0-9]+)?)(MB|GB|MiB|GiB)$`)

func ParseSize(s string) (int64, error) {
	m := capacity.FindStringSubmatch(s)
	if m == nil {
		return 0, errors.New("cache.max_size must use MB, GB, MiB or GiB")
	}
	v, err := strconv.ParseFloat(m[1], 64)
	mult := map[string]float64{"MB": 1e6, "GB": 1e9, "MiB": 1 << 20, "GiB": 1 << 30}[m[2]]
	v *= mult
	if err != nil || math.IsInf(v, 0) || v < 1 || v >= math.MaxInt64 {
		return 0, errors.New("cache.max_size is outside the supported positive range")
	}
	return int64(v), nil
}

// Canonical resolves symlinks in existing ancestors, including paths whose leaf
// does not exist yet. A broken symlink or access error is never treated as absent.
func Canonical(path string) (string, error) {
	p, err := filepath.Abs(path)
	if err != nil {
		return "", err
	}
	suffix := []string{}
	for {
		_, err = os.Lstat(p)
		if err == nil {
			p, err = filepath.EvalSymlinks(p)
			if err != nil {
				return "", err
			}
			break
		}
		if !errors.Is(err, os.ErrNotExist) {
			return "", err
		}
		parent := filepath.Dir(p)
		if parent == p {
			return "", err
		}
		suffix = append(suffix, filepath.Base(p))
		p = parent
	}
	for i := len(suffix) - 1; i >= 0; i-- {
		p = filepath.Join(p, suffix[i])
	}
	return filepath.Clean(p), nil
}
func Contains(root, path string) bool {
	r, err := filepath.Rel(root, path)
	return err == nil && r != ".." && !strings.HasPrefix(r, ".."+string(filepath.Separator)) && !filepath.IsAbs(r)
}
func (c Config) IsIgnored(path string) bool {
	for _, p := range c.Ignore {
		if Contains(p.Path, path) {
			return true
		}
	}
	return false
}
