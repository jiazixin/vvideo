package jobs

import (
	"context"
	"sync"
	"sync/atomic"
	"time"
)

// Priority never holds its mutex while waiting, doing IO, or acquiring a writer.
type Priority struct {
	mu      sync.Mutex
	active  int
	lastEnd time.Time
	Now     func() time.Time
	changed chan struct{}
	serial  atomic.Int64
}

func NewPriority() *Priority { return &Priority{Now: time.Now, changed: make(chan struct{})} }
func (p *Priority) Begin() func() {
	p.mu.Lock()
	p.active++
	p.serial.Add(1)
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

// Serial changes when foreground media invalidates a background observation.
func (p *Priority) Serial() int64 { return p.serial.Load() }
