package jobs

import (
	"context"
	"sync"
	"time"
)

// playbackSettle is how long list reordering keeps waiting after the last
// media transfer ends, so a viewer that pauses between items still sees a
// stable list.
const playbackSettle = 10 * time.Second

// Priority tracks active media transfers. Only list reordering (capture-time
// sort keys and album anchors) waits for it, so the feed being watched does not
// shift; scans, probes and covers keep running during playback. It never holds
// its mutex while waiting, doing IO, or acquiring a writer.
type Priority struct {
	// Now is the clock; tests replace it before concurrent use.
	Now func() time.Time

	mu      sync.Mutex
	active  int
	lastEnd time.Time
	// changed is closed and replaced whenever active changes, waking Wait.
	changed chan struct{}
}

// NewPriority returns a Priority with no active transfers.
func NewPriority() *Priority { return &Priority{Now: time.Now, changed: make(chan struct{})} }

// Begin records the start of a media transfer. Call the returned function when
// it ends; extra calls are ignored.
func (p *Priority) Begin() func() {
	p.mu.Lock()
	p.active++
	p.signal()
	p.mu.Unlock()
	return sync.OnceFunc(func() {
		p.mu.Lock()
		p.active--
		p.lastEnd = p.Now()
		p.signal()
		p.mu.Unlock()
	})
}

// signal wakes every Wait call; the caller holds mu.
func (p *Priority) signal() {
	close(p.changed)
	p.changed = make(chan struct{})
}

// Ready reports whether no transfer is active and playbackSettle has passed
// since the last one ended.
func (p *Priority) Ready() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	ready, _ := p.readyLocked()
	return ready
}

// Wait blocks until Ready would report true or ctx is cancelled.
func (p *Priority) Wait(ctx context.Context) error {
	for {
		p.mu.Lock()
		ready, wait := p.readyLocked()
		changed := p.changed
		p.mu.Unlock()
		if ready {
			return ctx.Err()
		}
		timer := time.NewTimer(max(wait, time.Millisecond))
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-changed:
			timer.Stop()
		case <-timer.C:
		}
	}
}

// readyLocked reports readiness and, when not ready, how long to wait before
// checking again. The caller holds mu.
func (p *Priority) readyLocked() (bool, time.Duration) {
	if p.active > 0 {
		return false, time.Second
	}
	if p.lastEnd.IsZero() {
		return true, 0
	}
	remaining := playbackSettle - p.Now().Sub(p.lastEnd)
	return remaining <= 0, remaining
}
