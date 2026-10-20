package scanner

import (
	"bufio"
	"database/sql"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"testing"
	"time"
	"vvideo/internal/config"
	"vvideo/internal/jobs"
	"vvideo/internal/library"
	"vvideo/internal/storage"
)

// A separate process is killed while it owns real SQLite/OS resources. This
// verifies crash recovery rather than merely returning an injected Go error.
func TestScanProcessCrashRecovery(t *testing.T) {
	if base := os.Getenv("VV_CRASH_BASE"); base != "" {
		c, err := config.Load(config.Source{WorkDir: base, Env: []string{"VVIDEO_AUTH__TOKEN=test", fmt.Sprintf(`VVIDEO_MEDIA=[{"path":%q}]`, filepath.Join(base, "media"))}})
		if err != nil {
			t.Fatal(err)
		}
		db, err := storage.Open(t.Context(), c.Database())
		if err != nil {
			t.Fatal(err)
		}
		index := &library.Index{Store: db}
		if err = index.Recover(t.Context()); err != nil {
			t.Fatal(err)
		}
		ready := func() { fmt.Println("CRASH_READY"); time.Sleep(time.Hour) }
		switch os.Getenv("VV_CRASH_MODE") {
		case "transaction":
			err = db.Write(t.Context(), 0, func(tx *sql.Tx) error {
				if _, err := tx.Exec("UPDATE counters SET value=999999 WHERE name='thumbnail_bytes'"); err != nil {
					return err
				}
				ready()
				return nil
			})
		case "claim":
			q := &jobs.Queue{Store: db, Index: index, Now: time.Now}
			_, err = q.Claim(t.Context(), "probe")
			if err == nil {
				ready()
			}
		case "build":
			batches := 0
			s := &Scanner{Config: c, Index: index, Now: time.Now, Fault: func(at string) error {
				if at == "build" {
					batches++
					if batches == 2 {
						ready()
					}
				}
				return nil
			}}
			err = s.Run(t.Context(), false)
		default:
			t.Fatal("unknown crash mode")
		}
		t.Fatalf("crash point not reached: %v", err)
	}
	for _, mode := range []string{"transaction", "claim", "build"} {
		t.Run(mode, func(t *testing.T) {
			s, root := fixture(t)
			create(t, root, "old.mp4")
			s.Now = func() time.Time { return time.Now().Add(-2 * time.Minute) }
			if err := s.Run(t.Context(), false); err != nil {
				t.Fatal(err)
			}
			libs, err := s.Index.Libraries(t.Context())
			if err != nil {
				t.Fatal(err)
			}
			page, err := s.Index.List(t.Context(), libs[0].DirectoryID, library.ListOptions{})
			if err != nil || len(page.Items) != 1 {
				t.Fatal(err)
			}
			original := page.Items[0]
			if err = s.Index.Store.Close(); err != nil {
				t.Fatal(err)
			}
			if mode == "build" {
				for n := range 257 {
					create(t, root, fmt.Sprintf("new-%04d.mp4", n))
				}
			}
			cmd := exec.CommandContext(t.Context(), os.Args[0], "-test.run=^TestScanProcessCrashRecovery$", "-test.v")
			cmd.Env = append(os.Environ(), "VV_CRASH_BASE="+filepath.Dir(root), "VV_CRASH_MODE="+mode)
			stdout, err := cmd.StdoutPipe()
			if err != nil {
				t.Fatal(err)
			}
			cmd.Stderr = os.Stderr
			if err = cmd.Start(); err != nil {
				t.Fatal(err)
			}
			defer func() {
				if cmd.ProcessState == nil {
					cmd.Process.Kill()
					cmd.Wait()
				}
			}()
			ready := make(chan bool, 1)
			go func() {
				scan := bufio.NewScanner(stdout)
				for scan.Scan() {
					if scan.Text() == "CRASH_READY" {
						ready <- true
						return
					}
				}
				ready <- false
			}()
			select {
			case ok := <-ready:
				if !ok {
					t.Fatal("helper exited before crash point")
				}
			case <-time.After(10 * time.Second):
				t.Fatal("helper did not reach crash point")
			}
			if err = cmd.Process.Kill(); err != nil {
				t.Fatal(err)
			}
			if err = cmd.Wait(); err == nil {
				t.Fatal("helper exited normally instead of being killed")
			}
			db, err := storage.Open(t.Context(), s.Config.Database())
			if err != nil {
				t.Fatal("OS ownership lock survived crash", err)
			}
			defer db.Close()
			var check string
			if err = db.Reader.QueryRow("PRAGMA quick_check").Scan(&check); err != nil || check != "ok" {
				t.Fatal("integrity", err, check)
			}
			foreign, err := db.Reader.Query("PRAGMA foreign_key_check")
			if err != nil {
				t.Fatal(err)
			}
			hasViolation := foreign.Next()
			err = foreign.Err()
			foreign.Close()
			if hasViolation || err != nil {
				t.Fatal("foreign key violation", err)
			}
			index := &library.Index{Store: db}
			if err = index.Recover(t.Context()); err != nil {
				t.Fatal(err)
			}
			if mode == "transaction" {
				var n int
				if err = db.Reader.QueryRow("SELECT value FROM counters WHERE name='thumbnail_bytes'").Scan(&n); err != nil || n != 0 {
					t.Fatal("uncommitted change survived", err, n)
				}
			}
			if mode == "claim" {
				q := jobs.Queue{Store: db, Index: index, Now: time.Now}
				j, err := q.Claim(t.Context(), "probe")
				if err != nil || j.MediaID != original.ID || j.Version != original.Version || j.Attempts != 2 {
					t.Fatal("running lease was not recovered", err, j)
				}
			}
			current, err := index.List(t.Context(), libs[0].DirectoryID, library.ListOptions{})
			if err != nil || len(current.Items) != 1 || current.Items[0].Version != original.Version {
				t.Fatal("partial candidate exposed", err)
			}
			var builds int
			if err = db.Reader.QueryRow("SELECT count(*) FROM builds").Scan(&builds); err != nil || builds != 0 {
				t.Fatal("abandoned build retained", err)
			}
			if mode == "build" {
				s.Index = index
				s.Fault = nil
				if err = s.Run(t.Context(), false); err != nil {
					t.Fatal(err)
				}
				var n int
				if err = db.Reader.QueryRow("SELECT count(*) FROM entries e JOIN directories d ON d.id=e.directory_id WHERE e.generation=d.generation AND e.state='visible'").Scan(&n); err != nil || n != 258 {
					t.Fatal("rescan failed after crash", err, n)
				}
				restored, err := index.Media(t.Context(), original.ID)
				if err != nil || restored.Version != original.Version {
					t.Fatal("stable identity lost", err)
				}
			}
		})
	}
}

func TestFilesystemScaleFirstAndUnchanged(t *testing.T) {
	raw := os.Getenv("VV_SCAN_ITEMS")
	if raw == "" {
		t.Skip("explicit filesystem scale required")
	}
	n, err := strconv.Atoi(raw)
	if err != nil || n < 1 || n > 100000 {
		t.Fatal("invalid filesystem scale")
	}
	parallel, err := strconv.Atoi(os.Getenv("VV_SCAN_PARALLEL"))
	if err != nil || parallel < 1 || parallel > 4 {
		t.Fatal("explicit parallel must be 1..4")
	}
	s, root := fixture(t)
	s.Config.Scan.Parallel = parallel
	for i := range n {
		create(t, root, fmt.Sprintf("%08d.mp4", i))
	}
	for round := range 3 {
		if round == 2 {
			if err := os.WriteFile(filepath.Join(root, "00000000.mp4"), []byte("changed source with new size"), 0600); err != nil {
				t.Fatal(err)
			}
		}
		rows := s.Index.Store.Metrics.Rows.Load()
		began := time.Now()
		if err = s.Run(t.Context(), false); err != nil {
			t.Fatal(err)
		}
		if count(t, s) != n {
			t.Fatal("lost files")
		}
		delta := s.Index.Store.Metrics.Rows.Load() - rows
		if round == 2 && delta != 1 {
			t.Fatal("single change created extra versions", delta)
		}
		if round == 1 && delta != 0 {
			t.Fatal("unchanged new-version writes", delta)
		}
		t.Logf("items=%d parallel=%d round=%d elapsed=%s new_versions=%d max_tx=%s cumulative_phases_ns=readdir:%d/stat:%d/stage:%d/compare:%d/build:%d/publish:%d/cleanup:%d", n, parallel, round, time.Since(began), delta, time.Duration(s.Index.Store.Metrics.MaxTransactionNS.Load()), s.Metrics.ReadDirNS.Load(), s.Metrics.StatNS.Load(), s.Metrics.StageNS.Load(), s.Metrics.CompareNS.Load(), s.Metrics.BuildNS.Load(), s.Metrics.PublishNS.Load(), s.Metrics.CleanupNS.Load())
	}
	if s.Metrics.Peak.Load() > int64(parallel) {
		t.Fatal("global concurrency exceeded")
	}
}

func TestConfirmedMissingDoesNotRewriteOnSteadyRescan(t *testing.T) {
	s, root := fixture(t)
	create(t, root, "one.mp4")
	if err := s.Run(t.Context(), false); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(filepath.Join(root, "one.mp4")); err != nil {
		t.Fatal(err)
	}
	for range 2 {
		if err := s.Run(t.Context(), false); err != nil {
			t.Fatal(err)
		}
	}
	libs, err := s.Index.Libraries(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	before, err := s.Index.Directory(t.Context(), libs[0].DirectoryID)
	if err != nil {
		t.Fatal(err)
	}
	// A temporary audit trigger counts actual membership UPDATEs, independent of
	// the candidate-build instrumentation counter.
	if err = s.Index.Store.Write(t.Context(), 0, func(tx *sql.Tx) error {
		_, err := tx.Exec("CREATE TEMP TABLE scan_audit(n INTEGER);INSERT INTO scan_audit VALUES(0);CREATE TEMP TRIGGER audit_updates AFTER UPDATE ON main.entries BEGIN UPDATE scan_audit SET n=n+1;END;")
		return err
	}); err != nil {
		t.Fatal(err)
	}
	if err = s.Run(t.Context(), false); err != nil {
		t.Fatal(err)
	}
	after, err := s.Index.Directory(t.Context(), before.ID)
	if err != nil {
		t.Fatal(err)
	}
	if after.Revision != before.Revision {
		t.Fatal("steady missing tombstone invalidates cursors")
	}
	var updates int
	if err = s.Index.Store.Writer.QueryRow("SELECT n FROM scan_audit").Scan(&updates); err != nil || updates != 0 {
		t.Fatal("steady tombstone rewritten", err, updates)
	}
}

func TestStageCleanupIsScopedToOneStorage(t *testing.T) {
	s, root := fixture(t)
	original, err := newStage(t.Context(), s.Config.Storage)
	if err != nil {
		t.Fatal(err)
	}
	namespace := filepath.Dir(original.path)
	if err = original.Close(); err != nil {
		t.Fatal(err)
	}
	orphan := filepath.Join(namespace, "00000000-0000-4000-8000-000000000001.sqlite")
	unrelated := filepath.Join(namespace, "notes.sqlite")
	outside := filepath.Join(filepath.Dir(namespace), "other-storage", "00000000-0000-4000-8000-000000000002.sqlite")
	if err = os.MkdirAll(filepath.Dir(outside), 0700); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{orphan, unrelated, outside} {
		if err = os.WriteFile(path, []byte("owned fixture"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	symlink := filepath.Join(namespace, "00000000-0000-4000-8000-000000000003.sqlite")
	if err = os.Symlink(outside, symlink); err != nil {
		t.Fatal(err)
	}
	create(t, root, "a.mp4")
	if err = s.Run(t.Context(), false); err != nil {
		t.Fatal(err)
	}
	if _, err = os.Stat(orphan); !os.IsNotExist(err) {
		t.Fatal("crashed stage retained", err)
	}
	for _, path := range []string{unrelated, outside, symlink} {
		if _, err = os.Lstat(path); err != nil {
			t.Fatal("cleanup escaped owned stage namespace", err)
		}
	}
}
