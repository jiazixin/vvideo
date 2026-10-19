package main

import (
	"flag"
	"fmt"
	"io"
	"runtime"
	"runtime/debug"
	"strings"
)

// version is set for release builds:
//
//	go build -ldflags "-X main.version=v1.0.0" ./cmd/vvideo
//
// Otherwise the module version Go stamps from VCS is reported.
var version = "dev"

const usage = `vvideo - serve an existing media library to the VVideo Android app

Usage:
  vvideo [options]

Options:
  -c, --config <file>   Configuration file (default: ./configs/config.local.yaml)
  -v, --version         Print version information and exit
  -h, --help            Print this help and exit

Environment:
  CONFIG_FILE           Configuration file, used when --config is not given
  VVIDEO_<SECTION>__<KEY>
                        Override one setting, e.g. VVIDEO_AUTH__TOKEN or
                        VVIDEO_CACHE__PATH; takes precedence over the file

Relative paths inside the configuration file resolve against its directory.
ffmpeg (with libwebp) and ffprobe must be on PATH.
`

type options struct {
	configFile string // empty: CONFIG_FILE or the default file
	help       bool
	version    bool
}

// parseArgs accepts -x and --x spellings, "--config file" and "--config=file".
func parseArgs(args []string) (options, error) {
	var o options
	flags := flag.NewFlagSet("vvideo", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	setConfig := func(v string) error {
		if strings.TrimSpace(v) == "" {
			return fmt.Errorf("file path must not be empty")
		}
		o.configFile = v
		return nil
	}
	flags.Func("c", "", setConfig)
	flags.Func("config", "", setConfig)
	flags.BoolVar(&o.help, "h", false, "")
	flags.BoolVar(&o.help, "help", false, "")
	flags.BoolVar(&o.version, "v", false, "")
	flags.BoolVar(&o.version, "version", false, "")
	if err := flags.Parse(args); err != nil {
		return o, err
	}
	if flags.NArg() > 0 {
		return o, fmt.Errorf("unexpected argument %q", flags.Arg(0))
	}
	return o, nil
}

// versionString reports the release or VCS-derived version, commit and toolchain.
func versionString() string {
	v, commit, dirty := version, "", false
	if info, ok := debug.ReadBuildInfo(); ok {
		if v == "dev" && info.Main.Version != "" && info.Main.Version != "(devel)" {
			v = info.Main.Version
		}
		for _, s := range info.Settings {
			switch s.Key {
			case "vcs.revision":
				commit = s.Value[:min(12, len(s.Value))]
			case "vcs.modified":
				dirty = s.Value == "true"
			}
		}
	}
	details := []string{runtime.Version(), runtime.GOOS + "/" + runtime.GOARCH}
	// A VCS-derived module version already embeds the commit.
	if commit != "" && !strings.Contains(v, commit) {
		if dirty {
			commit += "-dirty"
		}
		details = append([]string{"commit " + commit}, details...)
	}
	return fmt.Sprintf("vvideo %s (%s)", v, strings.Join(details, ", "))
}
