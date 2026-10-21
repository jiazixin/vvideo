// Package config implements the strict, restart-only configuration contract.
package config

import (
	"bytes"
	"encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"maps"
	"math"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"

	"github.com/robfig/cron/v3"
	"go.yaml.in/yaml/v3"
)

// DefaultFile is the configuration file used, relative to the working
// directory, when neither --config nor CONFIG_FILE selects one.
const DefaultFile = "configs/config.local.yaml"

// DatabaseFile is the SQLite file name inside the storage directory.
const DatabaseFile = "data.sqlite"

const (
	maxFileSize = 1 << 20
	maxWorkers  = 1024
	maxDepth    = 8
)

// Path is one entry of the media or ignore list.
type Path struct {
	Path string `yaml:"path" json:"path"`
}

// Config is the validated service configuration. Paths are absolute and
// symlink-resolved.
type Config struct {
	Server struct {
		Listen string // host or IP address, without a port
		Port   int
	}
	Auth struct{ Token string }
	// Storage is the directory holding DatabaseFile and the scan staging area.
	Storage       string
	Media, Ignore []Path
	Scan          struct {
		Cron string
		// Parallel bounds file attribute reads; ProbeParallel bounds ffprobe jobs.
		Parallel, ProbeParallel int
	}
	Cache struct {
		Path              string
		MaxSize           int64
		Parallel, Threads int
		Percent           float64
	}
	Log struct {
		Level slog.Level
		// Format is "json" or "text"; Output is "stdout", "stderr" or "file".
		Format, Output string
		// File is the log file when Output is "file", otherwise empty.
		File string
	}
}

// Source describes where configuration is read from. Tests pass an explicit
// working directory and environment so they never touch process-wide state.
type Source struct {
	WorkDir string
	Env     []string
	// File is the --config value. When non-empty it takes precedence over
	// CONFIG_FILE and DefaultFile.
	File string
}

type fieldKind int

const (
	stringField fieldKind = iota
	intField
	numberField
	pathListField
)

var fields = map[string]fieldKind{
	"server.listen":       stringField,
	"server.port":         intField,
	"auth.token":          stringField,
	"storage":             stringField,
	"media":               pathListField,
	"ignore":              pathListField,
	"scan.cron":           stringField,
	"scan.parallel":       intField,
	"scan.probe_parallel": intField,
	"cache.path":          stringField,
	"cache.max_size":      stringField,
	"cache.parallel":      intField,
	"cache.threads":       intField,
	"cache.percent":       numberField,
	"log.level":           stringField,
	"log.format":          stringField,
	"log.output":          stringField,
	"log.file":            stringField,
}

var sections = []string{"server", "auth", "scan", "cache", "log"}

var defaults = map[string]string{
	"server.listen":       "0.0.0.0",
	"server.port":         "8080",
	"storage":             "data",
	"ignore":              "[]",
	"scan.cron":           "*/30 * * * *",
	"scan.parallel":       "2",
	"scan.probe_parallel": "2",
	"cache.path":          "cache",
	"cache.max_size":      "30GB",
	"cache.parallel":      "2",
	"cache.threads":       "2",
	"cache.percent":       "10",
	"log.level":           "info",
	"log.format":          "json",
	"log.output":          "stdout",
}

var logLevels = map[string]slog.Level{"debug": slog.LevelDebug, "info": slog.LevelInfo, "warn": slog.LevelWarn, "error": slog.LevelError}

var errRequired = errors.New("required setting missing")

// settings holds raw values by dotted field name. bases records the directory
// that relative paths in each value resolve against; absent means the working
// directory.
type settings struct {
	values map[string]string
	bases  map[string]string
}

// Load applies defaults, then the selected YAML file, then VVIDEO_* variables.
// Errors name the file, field or variable involved but never echo values that
// may be secret.
func Load(src Source) (Config, error) {
	cwd, err := filepath.Abs(src.WorkDir)
	if err != nil {
		return Config{}, err
	}
	env := environment(src.Env)
	file, explicit, err := selectFile(cwd, src.File, env)
	if err != nil {
		return Config{}, err
	}
	s := settings{values: maps.Clone(defaults), bases: map[string]string{}}
	loaded, err := s.readFile(file, explicit)
	if err != nil {
		return Config{}, err
	}
	if err = s.applyEnvironment(env, cwd); err != nil {
		return Config{}, err
	}
	c, err := s.build(cwd)
	if err != nil && !loaded && errors.Is(err, errRequired) {
		return Config{}, fmt.Errorf("%w (no configuration file at %s; pass --config <file> or set VVIDEO_* variables)", err, file)
	}
	return c, err
}

func environment(env []string) map[string]string {
	out := make(map[string]string, len(env))
	for _, item := range env {
		if k, v, ok := strings.Cut(item, "="); ok {
			out[k] = v
		}
	}
	return out
}

// selectFile returns the absolute configuration path and whether it was chosen
// explicitly. Only the implicit default may be absent.
func selectFile(cwd, flagFile string, env map[string]string) (string, bool, error) {
	path, explicit := flagFile, flagFile != ""
	if !explicit {
		var set bool
		path, set = env["CONFIG_FILE"]
		if set && strings.TrimSpace(path) == "" {
			return "", false, errors.New("CONFIG_FILE is set but empty")
		}
		explicit = set
	}
	if !explicit {
		path = DefaultFile
	}
	if !filepath.IsAbs(path) {
		path = filepath.Join(cwd, path)
	}
	return path, explicit, nil
}

func (s *settings) readFile(path string, explicit bool) (bool, error) {
	f, err := os.Open(path)
	if errors.Is(err, os.ErrNotExist) && !explicit {
		return false, nil
	}
	if err != nil {
		return false, err // *fs.PathError names the file
	}
	defer f.Close()
	data, err := io.ReadAll(io.LimitReader(f, maxFileSize+1))
	if err != nil {
		return false, err
	}
	if len(data) > maxFileSize {
		return false, fmt.Errorf("%s: file exceeds 1 MiB", path)
	}
	if err = s.readYAML(data, filepath.Dir(path)); err != nil {
		return false, fmt.Errorf("%s: %w", path, err)
	}
	return true, nil
}

func (s *settings) readYAML(data []byte, base string) error {
	dec := yaml.NewDecoder(bytes.NewReader(data))
	var doc yaml.Node
	if err := dec.Decode(&doc); err != nil {
		if errors.Is(err, io.EOF) {
			return errors.New("file is empty")
		}
		return err
	}
	var extra yaml.Node
	if err := dec.Decode(&extra); !errors.Is(err, io.EOF) {
		return errors.New("must contain exactly one YAML document")
	}
	if len(doc.Content) != 1 || doc.Content[0].Kind != yaml.MappingNode {
		return errors.New("top level must be a mapping")
	}
	return s.readMap(doc.Content[0], "", base, 0)
}

func (s *settings) readMap(n *yaml.Node, prefix, base string, depth int) error {
	if depth > maxDepth || n.Kind != yaml.MappingNode || n.Tag != "!!map" {
		return fmt.Errorf("line %d: %s must be a mapping", n.Line, prefix)
	}
	seen := map[string]bool{}
	for i := 0; i < len(n.Content); i += 2 {
		k, v := n.Content[i], n.Content[i+1]
		if k.Tag != "!!str" || k.Value == "<<" {
			return fmt.Errorf("line %d: invalid key", k.Line)
		}
		if seen[k.Value] {
			return fmt.Errorf("line %d: duplicate key %q", k.Line, k.Value)
		}
		seen[k.Value] = true
		name := k.Value
		if prefix != "" {
			name = prefix + "." + name
		}
		if v.Kind == yaml.AliasNode || v.Anchor != "" {
			return fmt.Errorf("line %d: %s: aliases and anchors are not allowed", v.Line, name)
		}
		kind, ok := fields[name]
		if !ok {
			if prefix == "" && slices.Contains(sections, name) {
				if err := s.readMap(v, name, base, depth+1); err != nil {
					return err
				}
				continue
			}
			return fmt.Errorf("line %d: unknown field %q", k.Line, name)
		}
		value, err := scalar(name, kind, v)
		if err != nil {
			return err
		}
		s.values[name] = value
		s.bases[name] = base
	}
	return nil
}

// scalar converts one YAML value to the raw string form shared with the
// environment. Path lists are normalized to their JSON environment form.
func scalar(name string, kind fieldKind, v *yaml.Node) (string, error) {
	switch kind {
	case pathListField:
		if v.Kind != yaml.SequenceNode || v.Tag != "!!seq" {
			return "", fmt.Errorf("line %d: %s must be a list of {path: ...} objects", v.Line, name)
		}
		paths := []Path{}
		for _, o := range v.Content {
			if o.Kind != yaml.MappingNode || o.Tag != "!!map" || o.Anchor != "" || len(o.Content) != 2 ||
				o.Content[0].Value != "path" || o.Content[0].Tag != "!!str" ||
				o.Content[1].Kind != yaml.ScalarNode || o.Content[1].Tag != "!!str" {
				return "", fmt.Errorf("line %d: %s entries must be {path: <string>}", o.Line, name)
			}
			paths = append(paths, Path{Path: o.Content[1].Value})
		}
		b, err := json.Marshal(paths)
		return string(b), err
	case intField:
		if v.Kind != yaml.ScalarNode || v.Tag != "!!int" {
			return "", fmt.Errorf("line %d: %s must be an integer", v.Line, name)
		}
	case numberField:
		if v.Kind != yaml.ScalarNode || (v.Tag != "!!int" && v.Tag != "!!float") {
			return "", fmt.Errorf("line %d: %s must be a number", v.Line, name)
		}
	default:
		if v.Kind != yaml.ScalarNode || v.Tag != "!!str" {
			return "", fmt.Errorf("line %d: %s must be a string", v.Line, name)
		}
	}
	return v.Value, nil
}

func (s *settings) applyEnvironment(env map[string]string, cwd string) error {
	for k, v := range env {
		rest, ok := strings.CutPrefix(k, "VVIDEO_")
		if !ok {
			continue
		}
		name := strings.ToLower(strings.ReplaceAll(rest, "__", "."))
		if _, ok := fields[name]; !ok {
			return fmt.Errorf("unknown environment variable %s", k)
		}
		s.values[name] = v
		s.bases[name] = cwd
	}
	return nil
}

func (s *settings) base(name, cwd string) string {
	if b := s.bases[name]; b != "" {
		return b
	}
	return cwd
}

func (s *settings) path(name, cwd string) (string, error) {
	v := s.values[name]
	if strings.TrimSpace(v) == "" {
		return "", fmt.Errorf("%s must not be empty", name)
	}
	if !filepath.IsAbs(v) {
		v = filepath.Join(s.base(name, cwd), v)
	}
	p, err := Canonical(v)
	if err != nil {
		return "", fmt.Errorf("%s: %w", name, err)
	}
	return p, nil
}

func (s *settings) pathList(name, cwd string) ([]Path, error) {
	raw := s.values[name]
	var list []Path
	if !strings.HasPrefix(strings.TrimSpace(raw), "[") || json.Unmarshal([]byte(raw), &list, json.RejectUnknownMembers(true)) != nil {
		return nil, fmt.Errorf("%s must be a JSON array of {\"path\": ...} objects", name)
	}
	for i := range list {
		p := list[i].Path
		if strings.TrimSpace(p) == "" {
			return nil, fmt.Errorf("%s[%d].path must not be empty", name, i)
		}
		if !filepath.IsAbs(p) {
			p = filepath.Join(s.base(name, cwd), p)
		}
		resolved, err := Canonical(p)
		if err != nil {
			return nil, fmt.Errorf("%s[%d].path: %w", name, i, err)
		}
		list[i].Path = resolved
	}
	return list, nil
}

func (s *settings) worker(name string) (int, error) {
	n, err := strconv.Atoi(s.values[name])
	if err != nil || n <= 0 || n > maxWorkers {
		return 0, fmt.Errorf("%s must be an integer from 1 to %d", name, maxWorkers)
	}
	return n, nil
}

func (s *settings) build(cwd string) (Config, error) {
	var c Config
	var err error
	c.Server.Listen = s.values["server.listen"]
	if err = validateListen(c.Server.Listen); err != nil {
		return c, err
	}
	c.Server.Port, err = strconv.Atoi(s.values["server.port"])
	if err != nil || c.Server.Port < 1 || c.Server.Port > 65535 {
		return c, errors.New("server.port must be an integer from 1 to 65535")
	}
	c.Auth.Token = s.values["auth.token"]
	if strings.TrimSpace(c.Auth.Token) == "" {
		return c, fmt.Errorf("auth.token: %w", errRequired)
	}
	if c.Storage, err = s.path("storage", cwd); err != nil {
		return c, err
	}
	if info, err := os.Stat(c.Storage); err == nil && !info.IsDir() {
		return c, fmt.Errorf("storage %s must be a directory; the database is %s inside it", c.Storage, DatabaseFile)
	}
	if c.Cache.Path, err = s.path("cache.path", cwd); err != nil {
		return c, err
	}
	if c.Scan.Parallel, err = s.worker("scan.parallel"); err != nil {
		return c, err
	}
	if c.Scan.ProbeParallel, err = s.worker("scan.probe_parallel"); err != nil {
		return c, err
	}
	if c.Cache.Parallel, err = s.worker("cache.parallel"); err != nil {
		return c, err
	}
	if c.Cache.Threads, err = s.worker("cache.threads"); err != nil {
		return c, err
	}
	c.Cache.Percent, err = strconv.ParseFloat(s.values["cache.percent"], 64)
	if err != nil || math.IsNaN(c.Cache.Percent) || math.IsInf(c.Cache.Percent, 0) || c.Cache.Percent < 0 || c.Cache.Percent >= 100 {
		return c, errors.New("cache.percent must be a number in [0, 100)")
	}
	if c.Cache.MaxSize, err = ParseSize(s.values["cache.max_size"]); err != nil {
		return c, err
	}
	c.Scan.Cron = s.values["scan.cron"]
	if c.Scan.Cron != "" {
		if _, err = ParseCron(c.Scan.Cron); err != nil {
			return c, fmt.Errorf("scan.cron must be a five-field cron expression with an optional CRON_TZ= prefix: %w", err)
		}
	}
	if c.Media, err = s.pathList("media", cwd); err != nil {
		return c, err
	}
	if len(c.Media) == 0 {
		return c, fmt.Errorf("media: %w (at least one root)", errRequired)
	}
	if c.Ignore, err = s.pathList("ignore", cwd); err != nil {
		return c, err
	}
	if err = s.buildLog(&c, cwd); err != nil {
		return c, err
	}
	return c, c.validatePaths()
}

func (s *settings) buildLog(c *Config, cwd string) error {
	level, ok := logLevels[s.values["log.level"]]
	if !ok {
		return errors.New("log.level must be debug, info, warn or error")
	}
	c.Log.Level = level
	c.Log.Format = s.values["log.format"]
	if c.Log.Format != "json" && c.Log.Format != "text" {
		return errors.New("log.format must be json or text")
	}
	c.Log.Output = s.values["log.output"]
	switch c.Log.Output {
	case "stdout", "stderr":
		if s.values["log.file"] != "" {
			return fmt.Errorf("log.file is only used when log.output is file, not %s", c.Log.Output)
		}
	case "file":
		if strings.TrimSpace(s.values["log.file"]) == "" {
			return errors.New("log.file must be set when log.output is file")
		}
		var err error
		if c.Log.File, err = s.path("log.file", cwd); err != nil {
			return err
		}
		if info, err := os.Stat(c.Log.File); err == nil && info.IsDir() {
			return fmt.Errorf("log.file %s is a directory", c.Log.File)
		}
	default:
		return errors.New("log.output must be stdout, stderr or file")
	}
	return nil
}

// validateListen accepts a host name or IP address; the port is server.port.
func validateListen(listen string) error {
	if strings.TrimSpace(listen) != listen || listen == "" {
		return errors.New("server.listen must be a nonempty host or IP address")
	}
	// A colon can only belong to an IPv6 address; anything else carries a port.
	if !strings.Contains(listen, ":") {
		return nil
	}
	if _, err := netip.ParseAddr(listen); err != nil {
		return fmt.Errorf("server.listen %q must be a host or IP address without a port; set the port in server.port", listen)
	}
	return nil
}

// Address is the host:port the service listens on.
func (c Config) Address() string {
	return net.JoinHostPort(c.Server.Listen, strconv.Itoa(c.Server.Port))
}

// Database is the SQLite file inside the storage directory.
func (c Config) Database() string {
	return filepath.Join(c.Storage, DatabaseFile)
}

// validatePaths keeps writable state out of the read-only media trees.
func (c Config) validatePaths() error {
	if Contains(c.Cache.Path, c.Storage) || Contains(c.Storage, c.Cache.Path) {
		return fmt.Errorf("storage %s and cache.path %s must not contain each other", c.Storage, c.Cache.Path)
	}
	writable := map[string]string{"storage": c.Storage, "cache.path": c.Cache.Path}
	if c.Log.File != "" {
		writable["log.file"] = c.Log.File
	}
	for i, root := range c.Media {
		for _, other := range c.Media[:i] {
			if Contains(root.Path, other.Path) || Contains(other.Path, root.Path) {
				return fmt.Errorf("media roots %s and %s overlap", other.Path, root.Path)
			}
		}
		for name, path := range writable {
			if Contains(root.Path, path) || Contains(path, root.Path) {
				return fmt.Errorf("%s %s overlaps media root %s; writable data must stay outside media", name, path, root.Path)
			}
		}
	}
	for _, ignored := range c.Ignore {
		inside := slices.ContainsFunc(c.Media, func(root Path) bool {
			return ignored.Path != root.Path && Contains(root.Path, ignored.Path)
		})
		if !inside {
			return fmt.Errorf("ignore path %s must be strictly inside a media root", ignored.Path)
		}
	}
	return nil
}

// StageDir is the disposable scan staging directory inside the storage directory.
func StageDir(storage string) string {
	return filepath.Join(storage, ".scan-tmp")
}

var cronParser = cron.NewParser(cron.Minute | cron.Hour | cron.Dom | cron.Month | cron.Dow)

// ParseCron accepts five-field expressions with an optional CRON_TZ= prefix.
func ParseCron(s string) (cron.Schedule, error) {
	return cronParser.Parse(s)
}

var capacity = regexp.MustCompile(`^([0-9]+(?:\.[0-9]+)?)(MB|GB|MiB|GiB)$`)

var units = map[string]float64{"MB": 1e6, "GB": 1e9, "MiB": 1 << 20, "GiB": 1 << 30}

// ParseSize parses a cache.max_size value such as "30GB" or "512MiB" into bytes.
func ParseSize(s string) (int64, error) {
	m := capacity.FindStringSubmatch(s)
	if m == nil {
		return 0, fmt.Errorf("cache.max_size %q must be a number followed by MB, GB, MiB or GiB", s)
	}
	n, err := strconv.ParseFloat(m[1], 64)
	v := n * units[m[2]]
	if err != nil || math.IsInf(v, 0) || v < 1 || v >= math.MaxInt64 {
		return 0, fmt.Errorf("cache.max_size %q is outside the supported positive range", s)
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
	for _, part := range slices.Backward(suffix) {
		p = filepath.Join(p, part)
	}
	return filepath.Clean(p), nil
}

// Contains reports whether path is root or lies inside it.
func Contains(root, path string) bool {
	r, err := filepath.Rel(root, path)
	return err == nil && r != ".." && !strings.HasPrefix(r, ".."+string(filepath.Separator)) && !filepath.IsAbs(r)
}

// IsIgnored reports whether path is, or lies inside, an ignored path.
func (c Config) IsIgnored(path string) bool {
	return slices.ContainsFunc(c.Ignore, func(p Path) bool { return Contains(p.Path, path) })
}
