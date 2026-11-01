package jobs

import (
	"context"
	"errors"
	"testing"
	"testing/synctest"
	"time"
)

func TestPlaybackCooldown(t *testing.T) {
	t.Parallel()
	synctest.Test(t, func(t *testing.T) {
		priority := NewPriority(time.Now)
		end := priority.Begin()
		if priority.Ready() {
			t.Fatal("list reordering ready during playback")
		}
		ctx, cancel := context.WithCancel(t.Context())
		cancel()
		if err := priority.Wait(ctx); !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
		end()
		end()
		time.Sleep(9 * time.Second)
		if priority.Ready() {
			t.Fatal("early cooldown")
		}
		time.Sleep(time.Second)
		if !priority.Ready() {
			t.Fatal("cooldown did not finish")
		}
	})
}
