package jobs

import (
	"context"
	"sync"
	"time"
)

// Priority tracks active media transfers. Only list reordering (capture-time
// sort keys and album anchors) waits for it, so the feed being watched does not
// shift; scans, probes and covers keep running during playback. It never holds
// its mutex while waiting, doing IO, or acquiring a writer.
type Priority struct {
	mu      sync.Mutex
	active  int
	lastEnd time.Time
	Now     func() time.Time
	changed chan struct{}
}

func NewPriority() *Priority { return &Priority{Now: time.Now, changed: make(chan struct{})} }
func (p *Priority) Begin() func() {
	p.mu.Lock()
	p.active++
	p.signal()
	p.mu.Unlock()
	var once sync.Once
	return func() { once.Do(func() { p.mu.Lock(); p.active--; p.lastEnd = p.Now(); p.signal(); p.mu.Unlock() }) }
}
func (p *Priority) signal() { close(p.changed); p.changed = make(chan struct{}) }
func (p *Priority) Ready() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.active == 0 && (p.lastEnd.IsZero() || p.Now().Sub(p.lastEnd) >= 10*time.Second)
}
func (p *Priority) Wait(ctx context.Context) error {
	for {
		p.mu.Lock()
		ready := p.active == 0 && (p.lastEnd.IsZero() || p.Now().Sub(p.lastEnd) >= 10*time.Second)
		ch := p.changed
		wait := 10*time.Second - p.Now().Sub(p.lastEnd)
		if p.active > 0 {
			wait = time.Second
		}
		p.mu.Unlock()
		if ready {
			return ctx.Err()
		}
		timer := time.NewTimer(max(wait, time.Millisecond))
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-ch:
			timer.Stop()
		case <-timer.C:
		}
	}
}
