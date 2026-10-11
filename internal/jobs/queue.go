package jobs

import (
	"context"
	"database/sql"
	"errors"
	"time"
	"vvideo/internal/library"
	"vvideo/internal/storage"
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

func (q *Queue) Claim(ctx context.Context, kind string, foregroundOnly bool) (Job, error) {
	var j Job
	extra := ""
	if foregroundOnly {
		extra = " AND j.priority>0"
	}
	err := q.Store.Reader.QueryRowContext(ctx, `SELECT j.id,j.media_id,j.version,j.kind,j.parameters,j.fingerprint,j.first_observed,j.attempts FROM jobs j WHERE j.kind=? AND j.state='queued' AND j.due<=?`+extra+` AND `+currentJob+` ORDER BY j.due,j.id LIMIT 1`, kind, q.Now().UnixMilli()).Scan(&j.ID, &j.MediaID, &j.Version, &j.Kind, &j.Parameters, &j.Fingerprint, &j.FirstObserved, &j.Attempts)
	if err != nil {
		return j, err
	}
	err = q.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		var eligible bool
		if err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM jobs j WHERE j.id=? AND j.state='queued' AND j.due<=?`+extra+` AND `+currentJob+`)`, j.ID, q.Now().UnixMilli()).Scan(&eligible); err != nil {
			return err
		}
		if !eligible {
			return sql.ErrNoRows
		}
		_, err := tx.ExecContext(ctx, "UPDATE jobs SET state='running',lease=?,attempts=attempts+1 WHERE id=?", q.Now().Add(2*time.Minute).UnixMilli(), j.ID)
		return err
	})
	if err == nil {
		j.Attempts++
	}
	return j, err
}
func (q *Queue) Finish(ctx context.Context, j Job, state, code string) error {
	return q.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		due := q.Now().Add(30 * time.Second).UnixMilli()
		if state == "retry" {
			state = "queued"
			if j.Attempts >= 3 {
				state = "failed"
			}
		}
		_, err := tx.ExecContext(ctx, "UPDATE jobs SET state=?,error=?,due=?,lease=0 WHERE id=? AND state='running'", state, code, due, j.ID)
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
func (q *Queue) Worker(ctx context.Context, kind string, priority *Priority, run func(context.Context, Job) error) {
	for {
		if ctx.Err() != nil {
			return
		}
		if kind != "probe" || priority.Ready() {
			j, err := q.Claim(ctx, kind, !priority.Ready())
			if err == nil {
				e, err := q.Index.Media(ctx, j.MediaID)
				if err != nil || e.Version != j.Version {
					q.Finish(ctx, j, "stale", "version_unavailable")
				} else if err = run(ctx, j); err != nil {
					state := "retry"
					if errors.Is(err, library.ErrConflict) {
						state = "stale"
					}
					finishCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
					q.Finish(finishCtx, j, state, "tool_or_source_failure")
					cancel()
				}
				continue
			}
			if !errors.Is(err, sql.ErrNoRows) && ctx.Err() == nil {
				time.Sleep(200 * time.Millisecond)
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(200 * time.Millisecond):
		}
	}
}
