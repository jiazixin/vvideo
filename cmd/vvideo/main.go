package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
	"vvideo/internal/app"
	"vvideo/internal/config"
)

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))
	if err := run(); err != nil {
		slog.Error("service_failed", "reason", err.Error())
		os.Exit(1)
	}
}
func run() error {
	cwd, err := os.Getwd()
	if err != nil {
		return err
	}
	c, err := config.Load(cwd, os.Environ())
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	a, err := app.New(ctx, c)
	if err != nil {
		return errors.New("service initialization failed")
	}
	if err = a.Start(ctx); err != nil {
		stop()
		a.Close()
		return err
	}
	server := &http.Server{Addr: c.Server.Listen, Handler: a.Handler(), ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 << 10}
	done := make(chan error, 1)
	go func() { slog.Info("service_listening", "listen", c.Server.Listen); done <- server.ListenAndServe() }()
	select {
	case err = <-done:
		stop()
	case <-ctx.Done():
	}
	shutdown, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	server.Shutdown(shutdown)
	closed := make(chan error, 1)
	go func() { closed <- a.Close() }()
	select {
	case closeErr := <-closed:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			return err
		}
		return closeErr
	case <-shutdown.Done():
		server.Close()
		return errors.New("graceful shutdown exceeded 30 seconds")
	}
}
