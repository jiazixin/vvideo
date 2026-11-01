package app

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"vvideo/internal/storage"
)

func TestShutdownWaitsBeforeClosingStorage(t *testing.T) {
	t.Parallel()
	store, err := storage.Open(t.Context(), filepath.Join(t.TempDir(), "index.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := store.Close(); err != nil {
			t.Error(err)
		}
	})
	application := &App{Store: store, stopped: make(chan struct{})}
	release := make(chan struct{})
	application.wg.Go(func() { <-release })
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if err := application.Shutdown(ctx); !errors.Is(err, context.Canceled) {
		t.Fatalf("got %v, want context.Canceled", err)
	}
	var value int
	if err := store.QueryRow(t.Context(), "SELECT 1").Scan(&value); err != nil {
		t.Fatalf("storage closed underneath worker: %v", err)
	}
	close(release)
	ctx, cancel = context.WithTimeout(t.Context(), time.Second)
	defer cancel()
	if err := application.Shutdown(ctx); err != nil {
		t.Fatal(err)
	}
	if err := application.Shutdown(ctx); err != nil {
		t.Fatal(err)
	}
	if err := store.QueryRow(ctx, "SELECT 1").Scan(&value); err == nil {
		t.Fatal("storage remained open after shutdown")
	}
}

func TestPendingScanPreservesForce(t *testing.T) {
	t.Parallel()
	application := &App{scanRequests: make(chan struct{}, 1)}
	application.Trigger(false)
	application.Trigger(true)
	application.Trigger(false)
	select {
	case <-application.scanRequests:
		if !application.forceScan.Swap(false) {
			t.Fatal("forced scan was downgraded")
		}
	default:
		t.Fatal("no scan scheduled")
	}
	select {
	case <-application.scanRequests:
		t.Fatal("pending requests did not coalesce")
	default:
	}
}
