package main

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"strings"
)

const usage = `vvideo - serve an existing media library to the VVideo Android app

Usage:
  vvideo [options]

Options:
  -c, --config <file>  Configuration file (default: configs/config.local.yaml)
  -h, --help           Print this help and exit
      --version        Print version information and exit

Environment:
  CONFIG_FILE          Configuration file, used when --config is not given
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
			return errors.New("configuration path must not be empty")
		}
		if o.configFile != "" && o.configFile != v {
			return errors.New("-c and --config name different files")
		}
		o.configFile = v
		return nil
	}
	flags.Func("c", "", setConfig)
	flags.Func("config", "", setConfig)
	flags.BoolVar(&o.help, "h", false, "")
	flags.BoolVar(&o.help, "help", false, "")
	flags.BoolVar(&o.version, "version", false, "")
	if err := flags.Parse(args); err != nil {
		return options{}, err
	}
	if flags.NArg() > 0 {
		return options{}, fmt.Errorf("unexpected argument %q", flags.Arg(0))
	}
	return o, nil
}
