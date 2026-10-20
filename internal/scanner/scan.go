// Package scanner walks the media roots, observes each directory twice and
// publishes changed directories as new index generations. A failed or partial
// scan keeps the previous index; files count as missing only after two
// consecutive complete, healthy scans.
package scanner

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"time"

	"vvideo/internal/config"
	"vvideo/internal/library"
	"vvideo/internal/mediafs"
	"vvideo/internal/storage"
)

// maxWalkDepth bounds the directory stack; deeper branches fail the scan of
// their root and keep the previous index.
const maxWalkDepth = 1024

// Scanner walks media roots and publishes per-directory index generations.
// Only one Run is active at a time.
type Scanner struct {
	Config  config.Config
	Index   *library.Index
	Metrics Metrics
	running atomic.Bool
	// Now, Fault and Stat are test seams; nil selects the real clock, no
	// faults and mediafs.StatAt.
	Now   func() time.Time
	Fault func(string) error
	Stat  func(*os.File, string) (fs.FileInfo, error)
}

// Run scans every active library once. force treats every file as changed,
// so all of them get new versions and are probed again. It returns at once if
// a scan is already running.
func (s *Scanner) Run(ctx context.Context, force bool) error {
	if !s.running.CompareAndSwap(false, true) {
		return nil
	}
	defer s.running.Store(false)
	if s.Now == nil {
		s.Now = time.Now
	}
	if s.Stat == nil {
		s.Stat = mediafs.StatAt
	}
	stage, err := newStage(ctx, s.Config.Storage)
	if err != nil {
		return err
	}
	defer stage.Close()
	libs, err := s.Index.Libraries(ctx)
	if err != nil {
		return err
	}
	started, before := time.Now(), s.Metrics.snapshot()
	var failures []error
	for _, l := range libs {
		if err = s.scanRoot(ctx, stage, l, force); err != nil {
			failures = append(failures, fmt.Errorf("library %q (%s): %w", l.Name, l.ID, err))
			s.Metrics.Errors.Add(1)
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
	}
	run := s.Metrics.snapshot().since(before)
	slog.Info("scan_complete", "libraries", len(libs), "failed_libraries", len(failures),
		"directories", run.directories, "unchanged_directories", run.unchanged,
		"observed_entries", run.observed, "changed_entries", run.changed,
		"duration_ms", time.Since(started).Milliseconds())
	return errors.Join(failures...)
}

// scanRoot walks one library depth-first. Subdirectories wait in the stage's
// walk table rather than in memory, so the stack holds one frame per level.
// Only a complete walk of a healthy root marks the round healthy and lets
// missing items advance.
func (s *Scanner) scanRoot(ctx context.Context, st *stage, l library.Library, force bool) (result error) {
	// A completed filesystem round may have crashed during bounded missing-item
	// finalization. Resume its idempotent updates before starting another round.
	var prior, applied int64
	var healthyBefore bool
	if err := s.Index.Store.Reader.QueryRowContext(ctx, "SELECT round,applied_round,healthy FROM libraries WHERE id=?", l.ID).Scan(&prior, &applied, &healthyBefore); err != nil {
		return err
	}
	if healthyBefore && prior > applied {
		if err := s.finalizeMissing(ctx, l, prior); err != nil {
			return err
		}
	}
	var round int64
	if err := s.Index.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, "UPDATE libraries SET round=round+1 WHERE id=?", l.ID)
		if err != nil {
			return err
		}
		return tx.QueryRowContext(ctx, "SELECT round FROM libraries WHERE id=?", l.ID).Scan(&round)
	}); err != nil {
		return err
	}
	defer func() {
		if result != nil {
			// A failed round breaks the healthy streak, even after cancellation.
			ctx := context.WithoutCancel(ctx)
			s.Index.Store.Write(ctx, 0, func(tx *sql.Tx) error {
				_, err := tx.ExecContext(ctx, "UPDATE libraries SET healthy=0,streak=0,epoch=epoch+1 WHERE id=?", l.ID)
				return err
			})
		}
	}()
	root, err := mediafs.Open(l.Root)
	if err != nil {
		return fmt.Errorf("media root unavailable (offline, unmounted or not readable): %w", err)
	}
	defer root.Close()
	if l.Identity != "" && !root.Matches(l.Identity) {
		return errors.New("root identity differs from registered filesystem")
	}
	if l.Identity != root.Identity {
		if err = s.Index.Store.Write(ctx, 0, func(tx *sql.Tx) error {
			_, err := tx.ExecContext(ctx, "UPDATE libraries SET identity=? WHERE id=? AND identity=?", root.Identity, l.ID, l.Identity)
			return err
		}); err != nil {
			return err
		}
	}
	d, err := s.Index.Directory(ctx, l.DirectoryID)
	if err != nil {
		return err
	}
	// frame is one level of the walk: the directory and the name of the last
	// subdirectory descended into.
	type frame struct {
		dir     library.Directory
		after   string
		visited bool
	}
	stack := []frame{{dir: d}}
	healthy := true
	for len(stack) > 0 {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		f := &stack[len(stack)-1]
		if !f.visited {
			f.visited = true
			if err = s.scanDirectory(ctx, st, root, f.dir, round, force); err != nil {
				healthy = false
				stack = stack[:len(stack)-1]
				continue
			}
		}
		name, rel, id, err := st.nextSubdirectory(ctx, f.dir.ID, f.after)
		if errors.Is(err, sql.ErrNoRows) {
			st.forgetSubdirectories(ctx, f.dir.ID)
			stack = stack[:len(stack)-1]
			continue
		}
		if err != nil {
			return err
		}
		f.after = name
		if len(stack) >= maxWalkDepth {
			healthy = false
			continue
		}
		child, err := s.Index.Child(ctx, f.dir, rel, name, id, round)
		if err != nil {
			healthy = false
			continue
		}
		stack = append(stack, frame{dir: child})
	}
	if err = root.Validate(); err != nil {
		return err
	}
	if !healthy {
		return errors.New("incomplete root scan")
	}
	if err = s.Index.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, "UPDATE libraries SET healthy=1,streak=streak+1 WHERE id=?", l.ID)
		return err
	}); err != nil {
		return err
	}
	return s.finalizeMissing(ctx, l, round)
}

// videoExtensions and imageExtensions select the files that are indexed.
var (
	videoExtensions = map[string]bool{".mp4": true, ".mkv": true, ".mov": true, ".m4v": true, ".webm": true, ".avi": true, ".ts": true, ".m2ts": true}
	imageExtensions = map[string]bool{".jpg": true, ".jpeg": true, ".png": true, ".webp": true, ".gif": true, ".heic": true, ".heif": true, ".avif": true}
)

// Kind classifies a file name as "video" or "image" by extension, or returns
// "" for files that are not indexed.
func Kind(name string) string {
	ext := strings.ToLower(filepath.Ext(name))
	if videoExtensions[ext] {
		return "video"
	}
	if imageExtensions[ext] {
		return "image"
	}
	return ""
}

// scanDirectory observes directory d twice, publishes a new generation when
// its contents changed, and queues its subdirectories for the walk. It returns
// an error, keeping the previous index, when the directory cannot be observed
// consistently.
func (s *Scanner) scanDirectory(ctx context.Context, st *stage, root *mediafs.Root, d library.Directory, round int64, force bool) error {
	cleanupStarted := time.Now()
	if err := s.Index.Cleanup(ctx, d.ID); err != nil {
		return err
	}
	if err := s.Index.CleanupVersions(ctx, d.ID); err != nil {
		return err
	}
	s.Metrics.CleanupNS.Add(time.Since(cleanupStarted).Nanoseconds())
	// A directory that changes between the two passes gets one more try.
	for attempt := range 2 {
		original, consistent, err := s.observeTwice(ctx, st, root, d)
		if err != nil {
			return err
		}
		if !consistent {
			if attempt == 0 {
				s.Metrics.Retries.Add(1)
				continue
			}
			return errors.New("directory observations conflict")
		}
		if err = root.ValidateDirectory(d.Relative, original); err != nil {
			return err
		}
		comparedAt := time.Now()
		ignored := func(name string) bool { return s.Config.IsIgnored(filepath.Join(root.Path, d.Relative, name)) }
		resolve := func(name string) (string, error) { return s.Index.ResolveID(ctx, d.ID, name) }
		changed, err := st.merge(ctx, d, ignored, force, resolve)
		if err != nil {
			return err
		}
		s.Metrics.CompareNS.Add(time.Since(comparedAt).Nanoseconds())
		if changed > 0 || d.Generation == 0 {
			if err = s.publish(ctx, st, root, d, original, round); err != nil {
				return err
			}
			s.Metrics.Changed.Add(changed)
		} else if err = s.markUnchanged(ctx, d, round); err != nil {
			return err
		}
		if err = st.queueSubdirectories(ctx, d); err != nil {
			return err
		}
		s.Metrics.Directories.Add(1)
		return s.Index.Cleanup(ctx, d.ID)
	}
	return errors.New("observation failed")
}

// observeTwice stages d's published entries, then lists the directory twice
// and reports whether both passes saw the same entries. original describes the
// directory as first opened; a directory replaced between passes is an error.
func (s *Scanner) observeTwice(ctx context.Context, st *stage, root *mediafs.Root, d library.Directory) (original fs.FileInfo, consistent bool, err error) {
	if err = st.Reset(ctx); err != nil {
		return nil, false, err
	}
	after := ""
	for {
		batch, err := s.Index.Baseline(ctx, d, after)
		if err != nil {
			return nil, false, err
		}
		if len(batch) == 0 {
			break
		}
		stagedAt := time.Now()
		if err = st.baseline(ctx, batch); err != nil {
			return nil, false, err
		}
		s.Metrics.StageNS.Add(time.Since(stagedAt).Nanoseconds())
		after = batch[len(batch)-1].ID
	}
	for pass := 1; pass <= 2; pass++ {
		file, err := root.OpenFile(d.Relative)
		if err != nil {
			return nil, false, err
		}
		info, err := file.Stat()
		if err != nil {
			file.Close()
			return nil, false, err
		}
		if pass == 1 {
			original = info
		} else if !os.SameFile(original, info) {
			file.Close()
			return nil, false, errors.New("directory replaced")
		}
		err = s.observe(ctx, st, root, d, file, pass, original)
		file.Close()
		if err != nil {
			return nil, false, err
		}
	}
	comparedAt := time.Now()
	if consistent, err = st.equal(ctx); err != nil {
		return nil, false, err
	}
	s.Metrics.CompareNS.Add(time.Since(comparedAt).Nanoseconds())
	return original, consistent, nil
}

// publish writes the merged entries as a new generation of d and makes it
// visible, unless the directory changed on disk or another publication won.
func (s *Scanner) publish(ctx context.Context, st *stage, root *mediafs.Root, d library.Directory, original fs.FileInfo, round int64) error {
	start := time.Now()
	gen, err := s.Index.BeginBuild(ctx, d)
	if err != nil {
		return err
	}
	after := ""
	for {
		batch, err := st.merged(ctx, after)
		if err != nil {
			return err
		}
		if len(batch) == 0 {
			break
		}
		if err = s.fault("build"); err != nil {
			return err
		}
		if err = s.Index.AddBatch(ctx, d, gen, batch, s.Now()); err != nil {
			return err
		}
		after = batch[len(batch)-1].ID
	}
	s.Metrics.BuildNS.Add(time.Since(start).Nanoseconds())
	if err = s.fault("publish"); err != nil {
		return err
	}
	start = time.Now()
	validate := func() error { return root.ValidateDirectory(d.Relative, original) }
	if err = s.Index.Publish(ctx, d, gen, mediafs.Fingerprint(original), round, validate); err != nil {
		return err
	}
	s.Metrics.PublishNS.Add(time.Since(start).Nanoseconds())
	return nil
}

// markUnchanged records that d was observed in round without changes.
// Index.Child already marked subdirectories observed in this round; only the
// library root needs its own write.
func (s *Scanner) markUnchanged(ctx context.Context, d library.Directory, round int64) error {
	s.Metrics.Unchanged.Add(1)
	if d.ParentID != 0 {
		return nil
	}
	return s.Index.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, "UPDATE directories SET observed_round=?,state='visible',missing_streak=0 WHERE id=?", round, d.ID)
		return err
	})
}

// fault runs the test fault hook for step, if one is set.
func (s *Scanner) fault(step string) error {
	if s.Fault == nil {
		return nil
	}
	return s.Fault(step)
}

// observe reads one pass of directory file in pages of storage.BatchSize
// entries, stats the indexable ones with bounded parallelism and stages them.
func (s *Scanner) observe(ctx context.Context, st *stage, root *mediafs.Root, d library.Directory, file *os.File, pass int, original fs.FileInfo) error {
	for {
		if err := root.ValidateDirectory(d.Relative, original); err != nil {
			return err
		}
		start := time.Now()
		entries, readErr := file.ReadDir(storage.BatchSize)
		s.Metrics.ReadDirNS.Add(time.Since(start).Nanoseconds())
		selected := make([]fs.DirEntry, 0, len(entries))
		for _, e := range entries {
			if strings.HasPrefix(e.Name(), ".") || e.Type()&os.ModeSymlink != 0 || s.Config.IsIgnored(filepath.Join(root.Path, d.Relative, e.Name())) {
				continue
			}
			if !e.IsDir() && Kind(e.Name()) == "" {
				continue
			}
			selected = append(selected, e)
		}
		start = time.Now()
		attrs, err := Collect(ctx, selected, s.Config.Scan.Parallel, func(name string) (Attributes, error) {
			info, err := s.Stat(file, name)
			if err != nil {
				return Attributes{}, err
			}
			if !info.IsDir() && !info.Mode().IsRegular() {
				return Attributes{}, errors.New("unsupported special entry")
			}
			return Attributes{Name: name, Kind: Kind(name), Fingerprint: mediafs.Fingerprint(info), Size: info.Size(), Mtime: info.ModTime().UnixNano(), Directory: info.IsDir()}, nil
		}, &s.Metrics)
		s.Metrics.StatNS.Add(time.Since(start).Nanoseconds())
		if err != nil {
			return err
		}
		start = time.Now()
		if err = st.observe(ctx, pass, attrs); err != nil {
			return err
		}
		s.Metrics.StageNS.Add(time.Since(start).Nanoseconds())
		s.Metrics.Pages.Add(1)
		s.Metrics.Files.Add(int64(len(attrs)))
		if errors.Is(readErr, io.EOF) {
			break
		}
		if readErr != nil {
			return readErr
		}
	}
	return nil
}

// missingCandidate is one directory row as finalizeMissing reads it.
type missingCandidate struct {
	id, seen, streak int64
	rel, state       string
}

// finalizeMissing applies a completed healthy round of library l: it counts
// directories and entries not observed in round toward their missing streak
// and hides those missed in two consecutive healthy rounds, then records the
// round as applied. It is idempotent, so an interrupted run resumes safely.
func (s *Scanner) finalizeMissing(ctx context.Context, l library.Library, round int64) error {
	var epoch int64
	if err := s.Index.Store.Reader.QueryRowContext(ctx, "SELECT epoch FROM libraries WHERE id=?", l.ID).Scan(&epoch); err != nil {
		return err
	}
	var after int64
	for {
		batch, err := s.missingCandidates(ctx, l.ID, after)
		if err != nil {
			return err
		}
		if len(batch) == 0 {
			break
		}
		for _, x := range batch {
			after = x.id
			if err = s.markDirectoryMissing(ctx, l, x, round, epoch); err != nil {
				return err
			}
			if err = s.markEntriesMissing(ctx, x.id, round, epoch); err != nil {
				return err
			}
		}
	}
	return s.Index.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, "UPDATE libraries SET applied_round=? WHERE id=?", round, l.ID)
		return err
	})
}

// missingCandidates pages the directories of library by ID.
func (s *Scanner) missingCandidates(ctx context.Context, library string, after int64) ([]missingCandidate, error) {
	rows, err := s.Index.Store.Reader.QueryContext(ctx, "SELECT id,relative,observed_round,state,missing_streak FROM directories WHERE library_id=? AND id>? ORDER BY id LIMIT ?", library, after, storage.BatchSize)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	batch := []missingCandidate{}
	for rows.Next() {
		var x missingCandidate
		if err = rows.Scan(&x.id, &x.rel, &x.seen, &x.state, &x.streak); err != nil {
			return nil, err
		}
		batch = append(batch, x)
	}
	return batch, rows.Err()
}

// markDirectoryMissing marks directory x ignored when configuration now
// excludes it, or advances its missing streak when round did not observe it.
func (s *Scanner) markDirectoryMissing(ctx context.Context, l library.Library, x missingCandidate, round, epoch int64) error {
	ignore := s.Config.IsIgnored(filepath.Join(l.Root, x.rel))
	// Only this scan writes these columns, so the reader's view decides
	// whether a commit is needed; observed directories cost none.
	settled := x.seen == round || x.seen == -round || x.state == "missing"
	if ignore {
		settled = x.state == "ignored" && x.streak == 0
	}
	if settled {
		return nil
	}
	return s.Index.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		if ignore {
			_, err := tx.ExecContext(ctx, "UPDATE directories SET state='ignored',missing_streak=0 WHERE id=?", x.id)
			return err
		}
		var seen int64
		var state string
		if err := tx.QueryRowContext(ctx, "SELECT observed_round,state FROM directories WHERE id=?", x.id).Scan(&seen, &state); err != nil {
			return err
		}
		if seen == round || state == "missing" {
			return nil
		}
		// observed_round=-round records that this round was already counted,
		// so a resumed finalization does not count it twice.
		_, err := tx.ExecContext(ctx, `UPDATE directories SET
			missing_streak=CASE WHEN observed_round=-? THEN missing_streak WHEN missing_epoch!=? THEN 1 ELSE min(2,missing_streak+1) END,
			state=CASE WHEN missing_epoch=? AND missing_streak>=1 AND observed_round!=-? THEN 'missing' ELSE state END,
			missing_epoch=?,
			observed_round=-?
			WHERE id=?`, round, epoch, epoch, round, epoch, round, x.id)
		return err
	})
}

// markEntriesMissing advances the missing streak of the directory's published
// entries that were absent in round, one batch per transaction, bumping the
// directory revision with each batch.
func (s *Scanner) markEntriesMissing(ctx context.Context, directory, round, epoch int64) error {
	cursor := ""
	for {
		ids, err := s.absentEntries(ctx, directory, cursor)
		if err != nil {
			return err
		}
		if len(ids) == 0 {
			return nil
		}
		if err = s.Index.Store.Write(ctx, 0, func(tx *sql.Tx) error {
			stmt, err := tx.PrepareContext(ctx, `UPDATE entries SET
				missing_streak=CASE WHEN missing_epoch!=? THEN 1 ELSE min(2,missing_streak+1) END,
				missing_epoch=?,
				state=CASE WHEN missing_epoch=? AND missing_streak>=1 THEN 'missing' ELSE state END,
				observed_round=?
				WHERE directory_id=? AND generation=(SELECT generation FROM directories WHERE id=?) AND media_id=? AND observed_round!=?`)
			if err != nil {
				return err
			}
			defer stmt.Close()
			for _, id := range ids {
				if _, err = stmt.ExecContext(ctx, epoch, epoch, epoch, round, directory, directory, id, round); err != nil {
					return err
				}
			}
			_, err = tx.ExecContext(ctx, "UPDATE directories SET revision=revision+1 WHERE id=?", directory)
			return err
		}); err != nil {
			return err
		}
		cursor = ids[len(ids)-1]
	}
}

// absentEntries pages the IDs of the directory's visible published entries
// that the latest observation did not find.
func (s *Scanner) absentEntries(ctx context.Context, directory int64, after string) ([]string, error) {
	rows, err := s.Index.Store.Reader.QueryContext(ctx, `SELECT e.media_id FROM entries e
		JOIN directories d ON d.id=e.directory_id
		WHERE e.directory_id=? AND e.generation=d.generation AND e.present=0 AND e.state='visible' AND e.media_id>?
		ORDER BY e.media_id LIMIT ?`, directory, after, storage.BatchSize)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	ids := []string{}
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

// Running reports whether a scan is in progress.
func (s *Scanner) Running() bool { return s.running.Load() }
