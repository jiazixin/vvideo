// Command vvideo serves an existing media library to the VVideo Android app.
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
	"sync"
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

// run reports usage and startup errors as plain text on stderr; once the
// logger is ready, failures are logged.
func run(args []string, stdout, stderr io.Writer) int {
	o, err := parseArgs(args)
	if err != nil {
		fmt.Fprintf(stderr, "vvideo: %v\nRun 'vvideo --help' for usage.\n", err)
		return exitUsage
	}
	build := currentBuild()
	switch {
	case o.help:
		fmt.Fprint(stdout, usage)
		return exitOK
	case o.version:
		fmt.Fprintln(stdout, "vvideo", build)
		return exitOK
	}
	path, explicit, err := o.configPath(os.LookupEnv)
	if err != nil {
		fmt.Fprintf(stderr, "vvideo: config: %v\n", err)
		return exitFailure
	}
	workDir, err := os.Getwd()
	if err != nil {
		fmt.Fprintf(stderr, "vvideo: config: %v\n", err)
		return exitFailure
	}
	cfg, err := config.Load(config.Source{
		Path:      path,
		Explicit:  explicit,
		WorkDir:   workDir,
		LookupEnv: os.LookupEnv,
		Environ:   os.Environ(),
	})
	if err != nil {
		fmt.Fprintf(stderr, "vvideo: config: %v\n", err)
		return exitFailure
	}
	logger, closeLog, err := newLogger(cfg, stdout, stderr)
	if err != nil {
		fmt.Fprintf(stderr, "vvideo: log: %v\n", err)
		return exitFailure
	}
	defer func() {
		if err := closeLog(); err != nil {
			fmt.Fprintf(stderr, "vvideo: log: %v\n", err)
		}
	}()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	context.AfterFunc(ctx, stop) // a second signal terminates the process
	logger.Info("service_starting", "version", build.String())
	if err := serve(ctx, cfg, logger, build.String()); err != nil {
		logger.Error("service_failed", "error", err)
		return exitFailure
	}
	logger.Info("service_stopped")
	return exitOK
}

// serve runs the service until ctx ends or the HTTP server fails, then shuts
// it down.
func serve(ctx context.Context, c config.Config, logger *slog.Logger, version string) error {
	// Cancelling stops the background work, also when the HTTP server fails.
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	a, err := app.New(ctx, c, logger)
	if err != nil {
		return err
	}
	a.Version = version
	// Bind before starting background work so a busy port fails fast.
	listener, err := net.Listen("tcp", c.Address())
	if err != nil {
		return errors.Join(err, a.Shutdown(ctx))
	}
	if err = a.Start(ctx); err != nil {
		listener.Close() // the start error is the one to report
		cancel()
		stopCtx, stop := context.WithTimeout(context.WithoutCancel(ctx), shutdownTimeout)
		defer stop()
		return errors.Join(err, a.Shutdown(stopCtx))
	}
	server := &http.Server{
		Handler:           a.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		// Media streams extend their own write deadline after every write, so
		// this bounds stalled API responses without limiting long playback.
		ReadTimeout:    30 * time.Second,
		WriteTimeout:   60 * time.Second,
		IdleTimeout:    60 * time.Second,
		MaxHeaderBytes: 16 << 10,
		ErrorLog:       slog.NewLogLogger(logger.Handler(), slog.LevelError),
	}
	served := make(chan error, 1)
	var workers sync.WaitGroup
	workers.Go(func() { served <- server.Serve(listener) })
	logger.Info("service_listening", "listen", listener.Addr().String())
	select {
	case err = <-served:
	case <-ctx.Done():
		logger.Info("service_stopping")
	}
	cancel()
	stopErr := shutdown(ctx, server, a, logger)
	workers.Wait()
	if errors.Is(err, http.ErrServerClosed) {
		err = nil
	}
	return errors.Join(err, stopErr)
}

// shutdown drains HTTP requests within httpDrain and background work within
// shutdownTimeout.
func shutdown(ctx context.Context, server *http.Server, a *app.App, logger *slog.Logger) error {
	ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), shutdownTimeout)
	defer cancel()
	drain, stopDrain := context.WithTimeout(ctx, httpDrain)
	defer stopDrain()
	if server.Shutdown(drain) != nil {
		logger.Warn("http_drain_incomplete", "outcome", "remaining connections closed")
		if err := server.Close(); err != nil {
			logger.Warn("http_close_failed", "error", err)
		}
	}
	return a.Shutdown(ctx)
}
