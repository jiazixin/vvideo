package jobs

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"time"
	"vvideo/internal/library"
	"vvideo/internal/storage"
)

const (
	// MaxAttempts is how many times a failing job runs before it is marked failed.
	MaxAttempts = 3
	retryDelay  = 30 * time.Second
	leaseTime   = 2 * time.Minute
	idlePoll    = 200 * time.Millisecond
	// maxClaimBackoff caps the retry delay while claiming keeps failing.
	maxClaimBackoff = 30 * time.Second
)

type Job struct {
	ID                                              int64
	MediaID, Version, Kind, Parameters, Fingerprint string
	FirstObserved                                   int64
	Attempts                                        int
}
type Queue struct {
	Store *storage.Store
	Index *library.Index
	Now   func() time.Time
}

// Eligible jobs must belong to a published version and an available ancestor
// chain. The potentially large candidate search uses a read connection; the
// writer only revalidates and leases the one selected job.
const currentJob = `EXISTS(SELECT 1 FROM entries e JOIN directories d ON d.id=e.directory_id JOIN libraries l ON l.id=d.library_id WHERE e.media_id=j.media_id AND e.version=j.version AND e.generation=d.generation AND e.state='visible' AND l.active=1 AND NOT EXISTS(WITH RECURSIVE ancestry(id,parent_id,state,depth) AS (SELECT d.id,d.parent_id,d.state,0 UNION ALL SELECT p.id,p.parent_id,p.state,a.depth+1 FROM directories p JOIN ancestry a ON p.id=a.parent_id WHERE a.depth<1023) SELECT 1 FROM ancestry WHERE state!='visible' OR (depth=1023 AND parent_id IS NOT NULL)))`

func (q *Queue) Claim(ctx context.Context, kind string) (Job, error) {
	var j Job
	err := q.Store.Reader.QueryRowContext(ctx, `SELECT j.id,j.media_id,j.version,j.kind,j.parameters,j.fingerprint,j.first_observed,j.attempts FROM jobs j WHERE j.kind=? AND j.state='queued' AND j.due<=? AND `+currentJob+` ORDER BY j.due,j.id LIMIT 1`, kind, q.Now().UnixMilli()).Scan(&j.ID, &j.MediaID, &j.Version, &j.Kind, &j.Parameters, &j.Fingerprint, &j.FirstObserved, &j.Attempts)
	if err != nil {
		return j, err
	}
	err = q.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		var eligible bool
		if err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM jobs j WHERE j.id=? AND j.state='queued' AND j.due<=? AND `+currentJob+`)`, j.ID, q.Now().UnixMilli()).Scan(&eligible); err != nil {
			return err
		}
		if !eligible {
			return sql.ErrNoRows
		}
		_, err := tx.ExecContext(ctx, "UPDATE jobs SET state='running',lease=?,attempts=attempts+1 WHERE id=?", q.Now().Add(leaseTime).UnixMilli(), j.ID)
		return err
	})
	if err == nil {
		j.Attempts++
	}
	return j, err
}

// Finish records a running job's outcome. "retry" requeues it after
// retryDelay until MaxAttempts, then fails it. A failed job also marks its
// media version failed so clients stop waiting for metadata or a cover.
func (q *Queue) Finish(ctx context.Context, j Job, state, code string) error {
	return q.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		due := q.Now().Add(retryDelay).UnixMilli()
		if state == "retry" {
			state = "queued"
			if j.Attempts >= MaxAttempts {
				state = "failed"
			}
		}
		result, err := tx.ExecContext(ctx, "UPDATE jobs SET state=?,error=?,due=?,lease=0 WHERE id=? AND state='running'", state, code, due, j.ID)
		if err != nil || state != "failed" {
			return err
		}
		if n, err := result.RowsAffected(); err != nil || n == 0 {
			return err
		}
		_, err = tx.ExecContext(ctx, "UPDATE versions SET status='failed',error=? WHERE media_id=? AND version=? AND status!='ready'", code, j.MediaID, j.Version)
		return err
	})
}

// Release returns a job interrupted by shutdown to the queue without counting
// the attempt against it.
func (q *Queue) Release(ctx context.Context, j Job) error {
	return q.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, "UPDATE jobs SET state='queued',attempts=max(0,attempts-1),lease=0 WHERE id=? AND state='running'", j.ID)
		return err
	})
}

func (q *Queue) Delay(ctx context.Context, j Job, fingerprint string) error {
	return q.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, "UPDATE jobs SET state='queued',due=?,first_observed=?,fingerprint=?,attempts=0,lease=0 WHERE id=?", q.Now().Add(60*time.Second).UnixMilli(), q.Now().UnixMilli(), fingerprint, j.ID)
		return err
	})
}
func (q *Queue) EnqueueThumbnail(ctx context.Context, e library.Entry, parameters string, foreground bool) error {
	priority := 0
	if foreground {
		priority = 1
	}
	return q.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, `INSERT INTO jobs(media_id,version,kind,parameters,due,priority) VALUES(?,?,'thumbnail',?,?,?) ON CONFLICT(media_id,version,kind,parameters) DO UPDATE SET priority=max(priority,excluded.priority),attempts=CASE WHEN state='done' THEN 0 ELSE attempts END,due=CASE WHEN state='done' THEN excluded.due ELSE due END,state=CASE WHEN state='done' THEN 'queued' ELSE state END`, e.ID, e.Version, parameters, q.Now().UnixMilli(), priority)
		return err
	})
}

// Worker claims and runs jobs of one kind until ctx is cancelled. It does not
// yield to media playback; the worker count bounds its I/O instead.
func (q *Queue) Worker(ctx context.Context, kind string, run func(context.Context, Job) error) {
	wait := idlePoll
	for ctx.Err() == nil {
		claimed, err := q.runNext(ctx, kind, run)
		if claimed {
			wait = idlePoll
			continue
		}
		if err != nil && ctx.Err() == nil {
			// A failing database is retried with backoff, logged once per step.
			wait = min(wait*2, maxClaimBackoff)
			slog.Warn("job_claim_failed", "kind", kind, "retry_in", wait.String(), "error", err.Error())
		} else if err == nil {
			wait = idlePoll
		}
		select {
		case <-ctx.Done():
		case <-time.After(wait):
		}
	}
}

// runNext reports whether it claimed a job, or the claim error other than
// "nothing to do".
func (q *Queue) runNext(ctx context.Context, kind string, run func(context.Context, Job) error) (bool, error) {
	j, err := q.Claim(ctx, kind)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if e, err := q.Index.Media(ctx, j.MediaID); err != nil || e.Version != j.Version {
		q.Finish(ctx, j, "stale", "version_unavailable")
		return true, nil
	}
	if err = run(ctx, j); err == nil {
		return true, nil
	}
	finishCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
	defer cancel()
	switch {
	case ctx.Err() != nil:
		q.Release(finishCtx, j)
	case errors.Is(err, library.ErrConflict):
		q.Finish(finishCtx, j, "stale", "version_changed")
	default:
		if j.Attempts >= MaxAttempts {
			slog.Warn("job_failed", "kind", kind, "media_id", j.MediaID, "attempts", j.Attempts, "error", err.Error())
		}
		q.Finish(finishCtx, j, "retry", kind+"_failed")
	}
	return true, nil
}
