// Command vvideo is the VVideo media server.
package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"vvideo/internal/app"
	"vvideo/internal/config"
)

const (
	exitOK      = 0
	exitFailure = 1
	exitUsage   = 2

	shutdownTimeout = 30 * time.Second
	// httpDrain is how long in-flight requests may finish after a stop
	// signal. Connections still open then, such as a stream to a client that
	// stopped reading, are closed so background work gets the rest of
	// shutdownTimeout.
	httpDrain = 10 * time.Second
)

func main() {
	os.Exit(run(os.Args[1:], os.Stdout, os.Stderr))
}

// run handles command-line usage with plain text. Configuration errors are
// logged as JSON on stdout; once the configuration loads, everything goes to
// the configured log output, level and format.
func run(args []string, stdout, stderr io.Writer) int {
	o, err := parseArgs(args)
	if err != nil {
		fmt.Fprintf(stderr, "vvideo: %v\nRun 'vvideo --help' for usage.\n", err)
		return exitUsage
	}
	switch {
	case o.help:
		fmt.Fprint(stdout, usage)
		return exitOK
	case o.version:
		fmt.Fprintln(stdout, versionString())
		return exitOK
	}
	slog.SetDefault(slog.New(slog.NewJSONHandler(stdout, nil)))
	c, err := loadConfig(o.configFile)
	if err != nil {
		slog.Error("service_failed", "error", err.Error())
		return exitFailure
	}
	logger, closeLog, err := newLogger(c, stdout, stderr)
	if err != nil {
		slog.Error("service_failed", "error", "log.file: "+err.Error())
		return exitFailure
	}
	defer closeLog()
	slog.SetDefault(logger)
	if err := serve(c); err != nil {
		slog.Error("service_failed", "error", err.Error())
		return exitFailure
	}
	slog.Info("service_stopped")
	return exitOK
}

func loadConfig(configFile string) (config.Config, error) {
	cwd, err := os.Getwd()
	if err != nil {
		return config.Config{}, err
	}
	c, err := config.Load(config.Source{WorkDir: cwd, Env: os.Environ(), File: configFile})
	if err != nil {
		return c, fmt.Errorf("config: %w", err)
	}
	return c, nil
}

func serve(c config.Config) error {
	slog.Info("service_starting", "version", versionString())
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	a, err := app.New(ctx, c)
	if err != nil {
		return err
	}
	// Bind before starting background work so a busy port fails fast.
	listener, err := net.Listen("tcp", c.Address())
	if err != nil {
		a.Close()
		return err
	}
	if err = a.Start(ctx); err != nil {
		listener.Close()
		stop()
		a.Close()
		return err
	}
	server := &http.Server{
		Handler:           a.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		// Media streams extend their own write deadline after every write, so
		// this bounds stalled API responses without limiting long playback.
		WriteTimeout:   60 * time.Second,
		IdleTimeout:    60 * time.Second,
		MaxHeaderBytes: 16 << 10,
	}
	served := make(chan error, 1)
	go func() { served <- server.Serve(listener) }()
	slog.Info("service_listening", "listen", listener.Addr().String())
	select {
	case err = <-served:
	case <-ctx.Done():
		slog.Info("service_stopping")
	}
	stop()
	return errors.Join(err, shutdown(server, a))
}

// shutdown drains HTTP requests within httpDrain and background work within
// shutdownTimeout.
func shutdown(server *http.Server, a *app.App) error {
	ctx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	drain, stopDrain := context.WithTimeout(ctx, httpDrain)
	defer stopDrain()
	if server.Shutdown(drain) != nil {
		slog.Warn("http_drain_incomplete", "outcome", "remaining connections closed")
		server.Close()
	}
	closed := make(chan error, 1)
	go func() { closed <- a.Close() }()
	select {
	case err := <-closed:
		return err
	case <-ctx.Done():
		server.Close()
		return fmt.Errorf("graceful shutdown exceeded %s", shutdownTimeout)
	}
}
