package scanner

import (
	"context"
	"io/fs"
	"sync"
	"sync/atomic"
)

type Attributes struct {
	Name, Kind, Fingerprint string
	Size, Mtime             int64
	Directory               bool
}

// Metrics are cumulative since process start; /v1/status exposes them.
type Metrics struct {
	Pages, Files, Directories, Unchanged, Changed, Errors, Retries, StatNS, ReadDirNS, StageNS, CompareNS, CleanupNS, BuildNS, PublishNS atomic.Int64
	Current, Peak                                                                                                                        atomic.Int64
}

// runCounts is a point-in-time copy of the counters a scan summary reports.
// observed counts entries read in both observation passes.
type runCounts struct{ directories, unchanged, observed, changed int64 }

func (m *Metrics) snapshot() runCounts {
	return runCounts{m.Directories.Load(), m.Unchanged.Load(), m.Files.Load(), m.Changed.Load()}
}

func (c runCounts) since(before runCounts) runCounts {
	return runCounts{c.directories - before.directories, c.unchanged - before.unchanged, c.observed - before.observed, c.changed - before.changed}
}

func (m *Metrics) enter() {
	n := m.Current.Add(1)
	for old := m.Peak.Load(); n > old; old = m.Peak.Load() {
		if m.Peak.CompareAndSwap(old, n) {
			break
		}
	}
}

// Collect joins every dispatched worker before returning, including on failure.
func Collect(ctx context.Context, entries []fs.DirEntry, parallel int, stat func(string) (Attributes, error), metrics *Metrics) ([]Attributes, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	out := make([]Attributes, len(entries))
	tasks := make(chan int)
	var wg sync.WaitGroup
	var first error
	var mu sync.Mutex
	for range min(parallel, len(entries)) {
		wg.Go(func() {
			for index := range tasks {
				if ctx.Err() != nil {
					continue
				}
				metrics.enter()
				a, err := stat(entries[index].Name())
				metrics.Current.Add(-1)
				if err != nil {
					mu.Lock()
					if first == nil {
						first = err
					}
					mu.Unlock()
					cancel()
					continue
				}
				out[index] = a
			}
		})
	}
dispatch:
	for i := range entries {
		select {
		case <-ctx.Done():
			break dispatch
		case tasks <- i:
		}
	}
	close(tasks)
	wg.Wait()
	if first != nil {
		return nil, first
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return out, nil
}
