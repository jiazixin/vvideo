package jobs

import (
	"context"
	"database/sql"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"vvideo/internal/library"
	"vvideo/internal/storage"
)

func claimedFixture(t *testing.T) (*Queue, Job) {
	t.Helper()
	s, err := storage.Open(t.Context(), filepath.Join(t.TempDir(), "index.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := s.Close(); err != nil {
			t.Error(err)
		}
	})
	err = s.Write(t.Context(), 0, func(tx *sql.Tx) error {
		for _, statement := range []string{
			`INSERT INTO libraries(id,root,name) VALUES('library','/fixture','fixture')`,
			`INSERT INTO directories(id,library_id,relative,name) VALUES(1,'library','','fixture')`,
			`INSERT INTO media(id,directory_id,name) VALUES('media',1,'clip.mp4')`,
			`INSERT INTO versions(media_id,version,fingerprint,size,mtime,kind,effective_time) VALUES('media','version','fingerprint',1,0,'video',0)`,
			`INSERT INTO entries(directory_id,generation,media_id,version,name,kind,effective_time,mtime) VALUES(1,0,'media','version','clip.mp4','video',0,0)`,
			`INSERT INTO jobs(id,media_id,version,kind,parameters,due) VALUES(1,'media','version','probe','',0)`,
		} {
			if _, err := tx.ExecContext(t.Context(), statement); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	queue := NewQueue(&library.Index{Store: s}, nil, time.Now)
	job, err := queue.Claim(t.Context(), "probe")
	if err != nil {
		t.Fatal(err)
	}
	return queue, job
}

func TestClaimedJobLookupFailureDoesNotDiscardWork(t *testing.T) {
	for _, canceled := range []bool{false, true} {
		t.Run(map[bool]string{true: "cancelled", false: "database_error"}[canceled], func(t *testing.T) {
			t.Parallel()
			queue, job := claimedFixture(t)
			ctx, cancel := context.WithCancel(t.Context())
			defer cancel()
			if canceled {
				cancel()
			} else if err := queue.Store.Write(ctx, 0, func(tx *sql.Tx) error {
				_, err := tx.ExecContext(ctx, "DROP TABLE entries")
				return err
			}); err != nil {
				t.Fatal(err)
			}
			if err := queue.runClaimed(ctx, job, func(context.Context, Job) error {
				t.Error("tool ran after lookup failed")
				return nil
			}); err != nil {
				t.Fatal(err)
			}
			var state string
			var attempts int
			if err := queue.Store.QueryRow(t.Context(), "SELECT state,attempts FROM jobs WHERE id=1").Scan(&state, &attempts); err != nil {
				t.Fatal(err)
			}
			want := 1
			if canceled {
				want = 0
			}
			if state != "queued" || attempts != want {
				t.Fatalf("got %s/%d, want queued/%d", state, attempts, want)
			}
		})
	}
}

func TestClaimedJobOutcomeFailureIsReturned(t *testing.T) {
	t.Parallel()
	queue, job := claimedFixture(t)
	err := queue.runClaimed(t.Context(), job, func(ctx context.Context, _ Job) error {
		if err := queue.Store.Write(ctx, 0, func(tx *sql.Tx) error {
			_, err := tx.ExecContext(ctx, "DROP TABLE jobs")
			return err
		}); err != nil {
			t.Fatal(err)
		}
		return errors.New("tool failed")
	})
	if err == nil {
		t.Fatal("outcome write error was swallowed")
	}
}
