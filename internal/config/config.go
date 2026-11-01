// Package config implements the strict, restart-only configuration contract.
package config

import (
	"bytes"
	"cmp"
	"crypto/sha256"
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

// DatabaseFile is the SQLite file name inside the storage directory.
const DatabaseFile = "data.sqlite"

const (
	maxTokenLength = 4096
	maxFileSize    = 1 << 20
	maxWorkers     = 1024
	maxDepth       = 8
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
	Auth struct{ TokenDigest [sha256.Size]byte }
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

// Source describes where configuration comes from. Tests pass their own
// working directory and environment so they never touch process-wide state.
type Source struct {
	Path      string                      // selected file; empty when only defaults and variables apply
	Explicit  bool                        // chosen by --config or CONFIG_FILE
	WorkDir   string                      // base for a relative Path and for paths from defaults or variables
	LookupEnv func(string) (string, bool) // os.LookupEnv in production; nil means no variables
	// Environ lists the environment as KEY=value entries (os.Environ() in
	// production). Only the names are used, to reject VVIDEO_* variables that
	// match no field; values always come from LookupEnv. Nil skips the check.
	Environ []string
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

// fieldError reports an invalid setting by its dotted field path, such as
// "server.port: must be an integer between 1 and 65535".
type fieldError struct {
	field string
	err   error
}

func (e *fieldError) Error() string { return e.field + ": " + e.err.Error() }

func (e *fieldError) Unwrap() error { return e.err }

func invalid(field, message string) error {
	return &fieldError{field: field, err: errors.New(message)}
}

// settings holds raw values by dotted field name. bases records the directory
// that relative paths in each value resolve against; absent means the working
// directory.
type settings struct {
	values map[string]string
	bases  map[string]string
}

// Load applies defaults, then the selected YAML file, then VVIDEO_* variables,
// and validates the result. An explicit file must exist; a missing implicit
// file leaves the defaults and variables, which must then supply every required
// setting. Errors name the file, field or variable involved but never echo
// values that may be secret.
func Load(src Source) (Config, error) {
	cwd, err := filepath.Abs(src.WorkDir)
	if err != nil {
		return Config{}, fmt.Errorf("resolve working directory: %w", err)
	}
	s := settings{values: maps.Clone(defaults), bases: map[string]string{}}
	file, loaded := src.Path, false
	if file != "" || src.Explicit {
		if strings.TrimSpace(file) == "" {
			return Config{}, errors.New("configuration path is empty")
		}
		if !filepath.IsAbs(file) {
			file = filepath.Join(cwd, file)
		}
		if loaded, err = s.readFile(file, src.Explicit); err != nil {
			return Config{}, err
		}
	}
	if err = s.applyEnvironment(src, cwd); err != nil {
		return Config{}, err
	}
	c, err := s.decode(cwd)
	if err == nil {
		err = c.Validate()
	}
	if err != nil {
		if !loaded && errors.Is(err, errRequired) {
			missing := "no configuration file"
			if file != "" {
				missing += " at " + file
			}
			return Config{}, fmt.Errorf("%w (%s; pass --config <file> or set VVIDEO_* variables)", err, missing)
		}
		return Config{}, err
	}
	return c, nil
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
	return s.readMap(doc.Content[0], mappingLocation{base: base})
}

type mappingLocation struct {
	prefix, base string
	depth        int
}

func (s *settings) readMap(n *yaml.Node, location mappingLocation) error {
	prefix, base, depth := location.prefix, location.base, location.depth
	if depth > maxDepth || n.Kind != yaml.MappingNode || n.Tag != "!!map" {
		name := cmp.Or(prefix, "top level")
		return fmt.Errorf("line %d: %s: must be a mapping", n.Line, name)
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
				if err := s.readMap(v, mappingLocation{prefix: name, base: base, depth: depth + 1}); err != nil {
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
			return "", fmt.Errorf("line %d: %s: must be a list of {path: ...} objects", v.Line, name)
		}
		paths := []Path{}
		for _, o := range v.Content {
			if o.Kind != yaml.MappingNode || o.Tag != "!!map" || o.Anchor != "" || len(o.Content) != 2 ||
				o.Content[0].Value != "path" || o.Content[0].Tag != "!!str" ||
				o.Content[1].Kind != yaml.ScalarNode || o.Content[1].Tag != "!!str" {
				return "", fmt.Errorf("line %d: %s: entries must be {path: <string>}", o.Line, name)
			}
			paths = append(paths, Path{Path: o.Content[1].Value})
		}
		b, err := json.Marshal(paths)
		return string(b), err
	case intField:
		if v.Kind != yaml.ScalarNode || v.Tag != "!!int" {
			return "", fmt.Errorf("line %d: %s: must be an integer", v.Line, name)
		}
	case numberField:
		if v.Kind != yaml.ScalarNode || (v.Tag != "!!int" && v.Tag != "!!float") {
			return "", fmt.Errorf("line %d: %s: must be a number", v.Line, name)
		}
	default:
		if v.Kind != yaml.ScalarNode || v.Tag != "!!str" {
			return "", fmt.Errorf("line %d: %s: must be a string", v.Line, name)
		}
	}
	return v.Value, nil
}

// envName is the variable that overrides field, e.g. VVIDEO_SCAN__PROBE_PARALLEL.
func envName(field string) string {
	return "VVIDEO_" + strings.ToUpper(strings.ReplaceAll(field, ".", "__"))
}

func (s *settings) applyEnvironment(src Source, cwd string) error {
	for _, entry := range src.Environ {
		k, _, _ := strings.Cut(entry, "=")
		rest, ok := strings.CutPrefix(k, "VVIDEO_")
		if !ok {
			continue
		}
		name := strings.ToLower(strings.ReplaceAll(rest, "__", "."))
		if _, known := fields[name]; !known || k != envName(name) {
			return fmt.Errorf("unknown environment variable %s", k)
		}
	}
	if src.LookupEnv == nil {
		return nil
	}
	for name := range fields {
		if v, ok := src.LookupEnv(envName(name)); ok {
			s.values[name] = v
			s.bases[name] = cwd
		}
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
		return "", invalid(name, "must not be empty")
	}
	if !filepath.IsAbs(v) {
		v = filepath.Join(s.base(name, cwd), v)
	}
	p, err := Canonical(v)
	if err != nil {
		return "", &fieldError{field: name, err: err}
	}
	return p, nil
}

// pathList decodes a path list; an unset list without a default is empty.
func (s *settings) pathList(name, cwd string) ([]Path, error) {
	raw, ok := s.values[name]
	if !ok {
		return nil, nil
	}
	var list []Path
	if !strings.HasPrefix(strings.TrimSpace(raw), "[") || json.Unmarshal([]byte(raw), &list, json.RejectUnknownMembers(true)) != nil {
		return nil, invalid(name, `must be a JSON array of {"path": ...} objects`)
	}
	for i := range list {
		field := fmt.Sprintf("%s[%d].path", name, i)
		p := list[i].Path
		if strings.TrimSpace(p) == "" {
			return nil, invalid(field, "must not be empty")
		}
		if !filepath.IsAbs(p) {
			p = filepath.Join(s.base(name, cwd), p)
		}
		resolved, err := Canonical(p)
		if err != nil {
			return nil, &fieldError{field: field, err: err}
		}
		list[i].Path = resolved
	}
	return list, nil
}

var workerRange = fmt.Sprintf("must be an integer between 1 and %d", maxWorkers)

type workerField struct {
	name string
	n    *int
}

// workers lists the worker-count settings of c.
func workers(c *Config) []workerField {
	return []workerField{
		{"scan.parallel", &c.Scan.Parallel},
		{"scan.probe_parallel", &c.Scan.ProbeParallel},
		{"cache.parallel", &c.Cache.Parallel},
		{"cache.threads", &c.Cache.Threads},
	}
}

func (s *settings) worker(name string) (int, error) {
	n, err := strconv.Atoi(s.values[name])
	if err != nil {
		return 0, invalid(name, workerRange)
	}
	return n, nil
}

// decode converts the raw settings into a Config: numbers are parsed and paths
// made absolute and symlink-resolved. Range and consistency checks are left to
// Validate.
func (s *settings) decode(cwd string) (Config, error) {
	var c Config
	var err error
	c.Server.Listen = s.values["server.listen"]
	if c.Server.Port, err = strconv.Atoi(s.values["server.port"]); err != nil {
		return Config{}, invalid("server.port", "must be an integer between 1 and 65535")
	}
	if token := s.values["auth.token"]; strings.TrimSpace(token) != "" {
		if len(token) > maxTokenLength || strings.ContainsFunc(token, func(r rune) bool { return r < '!' || r > '~' }) {
			return Config{}, invalid("auth.token", "must contain 1 to 4096 visible ASCII characters without whitespace")
		}
		c.Auth.TokenDigest = sha256.Sum256([]byte(token))
	}
	if c.Storage, err = s.path("storage", cwd); err != nil {
		return Config{}, err
	}
	if c.Cache.Path, err = s.path("cache.path", cwd); err != nil {
		return Config{}, err
	}
	for _, w := range workers(&c) {
		if *w.n, err = s.worker(w.name); err != nil {
			return Config{}, err
		}
	}
	if c.Cache.Percent, err = strconv.ParseFloat(s.values["cache.percent"], 64); err != nil {
		return Config{}, invalid("cache.percent", "must be a number in [0, 100)")
	}
	if c.Cache.MaxSize, err = ParseSize(s.values["cache.max_size"]); err != nil {
		return Config{}, err
	}
	c.Scan.Cron = s.values["scan.cron"]
	if c.Media, err = s.pathList("media", cwd); err != nil {
		return Config{}, err
	}
	if c.Ignore, err = s.pathList("ignore", cwd); err != nil {
		return Config{}, err
	}
	level, ok := logLevels[s.values["log.level"]]
	if !ok {
		return Config{}, invalid("log.level", "must be debug, info, warn or error")
	}
	c.Log.Level = level
	c.Log.Format = s.values["log.format"]
	c.Log.Output = s.values["log.output"]
	// The file is resolved only when it is used; otherwise Validate rejects it.
	c.Log.File = s.values["log.file"]
	if c.Log.Output == "file" && strings.TrimSpace(c.Log.File) != "" {
		if c.Log.File, err = s.path("log.file", cwd); err != nil {
			return Config{}, err
		}
	}
	return c, nil
}

// Validate reports the first setting that is out of range, missing or in
// conflict with another. It does not modify c.
func (c Config) Validate() error {
	if err := validateListen(c.Server.Listen); err != nil {
		return err
	}
	if c.Server.Port < 1 || c.Server.Port > 65535 {
		return invalid("server.port", "must be an integer between 1 and 65535")
	}
	if c.Auth.TokenDigest == [sha256.Size]byte{} {
		return &fieldError{field: "auth.token", err: errRequired}
	}
	if !filepath.IsAbs(c.Storage) {
		return invalid("storage", "must be an absolute path")
	}
	if !filepath.IsAbs(c.Cache.Path) {
		return invalid("cache.path", "must be an absolute path")
	}
	if info, err := os.Stat(c.Storage); err == nil && !info.IsDir() {
		return &fieldError{field: "storage", err: fmt.Errorf("%s must be a directory; the database is %s inside it", c.Storage, DatabaseFile)}
	}
	for _, w := range workers(&c) {
		if *w.n <= 0 || *w.n > maxWorkers {
			return invalid(w.name, workerRange)
		}
	}
	if p := c.Cache.Percent; math.IsNaN(p) || math.IsInf(p, 0) || p < 0 || p >= 100 {
		return invalid("cache.percent", "must be a number in [0, 100)")
	}
	if c.Cache.MaxSize < 1 {
		return invalid("cache.max_size", "must be positive")
	}
	if c.Scan.Cron != "" {
		if _, err := ParseCron(c.Scan.Cron); err != nil {
			return &fieldError{field: "scan.cron", err: fmt.Errorf("must be a five-field cron expression with an optional CRON_TZ= prefix: %w", err)}
		}
	}
	if len(c.Media) == 0 {
		return &fieldError{field: "media", err: fmt.Errorf("%w (at least one root)", errRequired)}
	}
	if err := c.validateLog(); err != nil {
		return err
	}
	return c.validatePaths()
}

func (c Config) validateLog() error {
	if c.Log.Format != "json" && c.Log.Format != "text" {
		return invalid("log.format", "must be json or text")
	}
	switch c.Log.Output {
	case "stdout", "stderr":
		if c.Log.File != "" {
			return invalid("log.file", "only used when log.output is file, not "+c.Log.Output)
		}
	case "file":
		if strings.TrimSpace(c.Log.File) == "" {
			return invalid("log.file", "must be set when log.output is file")
		}
		if info, err := os.Stat(c.Log.File); err == nil && info.IsDir() {
			return &fieldError{field: "log.file", err: fmt.Errorf("%s is a directory", c.Log.File)}
		}
	default:
		return invalid("log.output", "must be stdout, stderr or file")
	}
	return nil
}

// validateListen accepts a host name or IP address; the port is server.port.
func validateListen(listen string) error {
	if strings.TrimSpace(listen) != listen || listen == "" {
		return invalid("server.listen", "must be a nonempty host or IP address")
	}
	// A colon can only belong to an IPv6 address; anything else carries a port.
	if !strings.Contains(listen, ":") {
		return nil
	}
	if _, err := netip.ParseAddr(listen); err != nil {
		return &fieldError{field: "server.listen", err: fmt.Errorf("%q must be a host or IP address without a port; set the port in server.port", listen)}
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
		return &fieldError{field: "cache.path", err: fmt.Errorf("%s and storage %s must not contain each other", c.Cache.Path, c.Storage)}
	}
	writable := map[string]string{"storage": c.Storage, "cache.path": c.Cache.Path}
	if c.Log.File != "" {
		writable["log.file"] = c.Log.File
	}
	for i, root := range c.Media {
		field := fmt.Sprintf("media[%d].path", i)
		for _, other := range c.Media[:i] {
			if Contains(root.Path, other.Path) || Contains(other.Path, root.Path) {
				return &fieldError{field: field, err: fmt.Errorf("%s overlaps media root %s", root.Path, other.Path)}
			}
		}
		for name, path := range writable {
			if Contains(root.Path, path) || Contains(path, root.Path) {
				return &fieldError{field: name, err: fmt.Errorf("%s overlaps media root %s; writable data must stay outside media", path, root.Path)}
			}
		}
	}
	for i, ignored := range c.Ignore {
		inside := slices.ContainsFunc(c.Media, func(root Path) bool {
			return ignored.Path != root.Path && Contains(root.Path, ignored.Path)
		})
		if !inside {
			return &fieldError{field: fmt.Sprintf("ignore[%d].path", i), err: fmt.Errorf("%s must be strictly inside a media root", ignored.Path)}
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
		return 0, &fieldError{field: "cache.max_size", err: fmt.Errorf("%q must be a number followed by MB, GB, MiB or GiB", s)}
	}
	n, err := strconv.ParseFloat(m[1], 64)
	v := n * units[m[2]]
	if err != nil || math.IsInf(v, 0) || v < 1 || v >= math.MaxInt64 {
		return 0, &fieldError{field: "cache.max_size", err: fmt.Errorf("%q is outside the supported positive range", s)}
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
