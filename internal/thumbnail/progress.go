package thumbnail

import (
	"context"
	"log/slog"
	"math"
	"time"
)

// Progress logs cover generation at info level, at most once per interval and
// only for intervals in which covers were published or attempts failed, so an
// idle queue writes nothing and the job table is not counted.
type Progress struct {
	Engine *Engine
	Logger *slog.Logger // nil: slog.Default()

	last                   time.Time
	generated, ns, failure int64
}

// Run reports every interval until ctx is cancelled.
func (p *Progress) Run(ctx context.Context, interval time.Duration) {
	p.Reset(time.Now())
	tick := time.Tick(interval)
	for {
		select {
		case <-ctx.Done():
			return
		case now := <-tick:
			if err := p.Report(ctx, now); err != nil && ctx.Err() == nil {
				p.logger().Warn("thumbnail_progress_failed", "error", err.Error())
			}
		}
	}
}

// Reset starts a reporting window at now from the current counters.
func (p *Progress) Reset(now time.Time) {
	m := &p.Engine.Metrics
	p.last, p.generated, p.ns, p.failure = now, m.Generated.Load(), m.GenerateNS.Load(), m.Failed.Load()
}

// Report closes the window ending at now. Totals come from the persistent
// queue, so they include work done before a restart; the window's counts,
// average and rate cover this process only.
func (p *Progress) Report(ctx context.Context, now time.Time) error {
	m := &p.Engine.Metrics
	generated := m.Generated.Load() - p.generated
	ns := m.GenerateNS.Load() - p.ns
	failed := m.Failed.Load() - p.failure
	window := now.Sub(p.last)
	if generated == 0 && failed == 0 {
		p.Reset(now)
		return nil
	}
	covers, err := p.Engine.Queue.Backlog(ctx, "thumbnail")
	if err != nil {
		return err
	}
	probes, err := p.Engine.Queue.Backlog(ctx, "probe")
	if err != nil {
		return err
	}
	p.Reset(now)
	total := covers.Done + covers.Failed + covers.Pending
	attrs := []slog.Attr{
		slog.String("window", window.Round(time.Second).String()),
		slog.Int64("generated", generated),
		slog.Int64("failed_attempts", failed),
		slog.Int64("total", total),
		slog.Int64("done", covers.Done),
		slog.Int64("failed", covers.Failed),
		slog.Int64("pending", covers.Pending),
		slog.Float64("percent", roundTenth(100*float64(covers.Done+covers.Failed)/float64(max(total, 1)))),
		slog.Int64("waiting_probe", probes.Pending),
	}
	if generated > 0 {
		attrs = append(attrs, slog.Int64("avg_ms", time.Duration(ns/generated).Milliseconds()))
	}
	if generated > 0 && window > 0 {
		attrs = append(attrs, slog.Float64("per_minute", roundTenth(float64(generated)/window.Minutes())))
		if covers.Pending > 0 {
			eta := time.Duration(float64(covers.Pending) / float64(generated) * float64(window))
			attrs = append(attrs, slog.String("eta", eta.Round(time.Minute).String()))
		}
	}
	p.logger().LogAttrs(ctx, slog.LevelInfo, "thumbnail_progress", attrs...)
	return nil
}

func (p *Progress) logger() *slog.Logger {
	if p.Logger != nil {
		return p.Logger
	}
	return slog.Default()
}

func roundTenth(v float64) float64 { return math.Round(v*10) / 10 }
