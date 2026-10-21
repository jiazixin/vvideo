// Package thumbnail generates one WebP cover per media version with FFmpeg and
// keeps the cover cache within its byte budget by LRU eviction.
package thumbnail

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"vvideo/internal/config"
	"vvideo/internal/jobs"
	"vvideo/internal/library"
	"vvideo/internal/mediafs"
	"vvideo/internal/probe"
	"vvideo/internal/storage"
)

// Encoder is the FFmpeg encoder that writes cover images.
const Encoder = "libwebp"

const (
	// coverTimeout bounds one FFmpeg run.
	coverTimeout = 60 * time.Second
	// maxCoverSize rejects implausibly large FFmpeg output.
	maxCoverSize = 8 << 20
	// coverReserve is the space set aside per cover worker before generating.
	coverReserve = 2 << 20
	// maxFreeSpaceGuard caps the free space eviction keeps on the cache
	// filesystem, which is otherwise 5% of its size.
	maxFreeSpaceGuard = 5_000_000_000
	// touchInterval coalesces cover accesses into one durable write.
	touchInterval = time.Minute
	// coverFilter scales covers to at most 640 px on either side and
	// coverQuality is the WebP quality; keep both in step with
	// probe.Parameters, which names them in the cache key.
	coverFilter  = "scale=w='min(640,iw)':h='min(640,ih)':force_original_aspect_ratio=decrease"
	coverQuality = "80"
)

// CheckEncoder verifies that the ffmpeg on PATH can write covers, so a build
// without libwebp fails at startup instead of failing every cover job.
func CheckEncoder(ctx context.Context) error {
	path, err := exec.LookPath("ffmpeg")
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	var stderr probe.Diagnostics
	cmd := exec.CommandContext(ctx, path, "-hide_banner", "-encoders")
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if err != nil {
		return stderr.Wrap(path+" -encoders", err)
	}
	for line := range strings.Lines(string(out)) {
		if f := strings.Fields(line); len(f) >= 2 && f[1] == Encoder {
			return nil
		}
	}
	return fmt.Errorf("%s has no %s encoder, which video covers require; install an FFmpeg build with libwebp (check: ffmpeg -hide_banner -encoders | grep %s)", path, Encoder, Encoder)
}

// Metrics counts this process's cover jobs since startup for progress logs.
type Metrics struct {
	Generated atomic.Int64
	// GenerateNS sums the run time of jobs that published a cover.
	GenerateNS atomic.Int64
	// Failed counts attempts that returned an error, excluding shutdown and
	// version changes; a job may fail several attempts before giving up.
	Failed atomic.Int64
}

// Engine generates, serves and evicts covers. pins counts open readers of
// each cover file and deleting marks files being evicted, so a cover is never
// removed while it is being served. budget serializes the byte ledger with
// publication, eviction and reconciliation.
type Engine struct {
	Config     config.Config
	Queue      *jobs.Queue
	Parameters string
	Metrics    Metrics
	mu         sync.Mutex
	pins       map[string]int
	deleting   map[string]bool
	budget     sync.Mutex
}

// New returns an Engine for the configured cache directory and cover settings.
func New(c config.Config, q *jobs.Queue) *Engine {
	return &Engine{Config: c, Queue: q, Parameters: probe.Parameters(c.Cache.Percent), pins: map[string]int{}, deleting: map[string]bool{}}
}

// Key is the cache file name stem of item's cover under the current settings.
func (e *Engine) Key(item library.Entry) string {
	return fmt.Sprintf("%x", sha256.Sum256([]byte(item.LibraryID+":"+item.ID+":"+item.Version+":"+e.Parameters)))
}

// Open opens item's cached cover and pins it against eviction until release is
// called. It fails with os.ErrNotExist when no cover is cached.
func (e *Engine) Open(item library.Entry) (*os.File, func(), error) {
	key := e.Key(item)
	e.mu.Lock()
	if e.deleting[key] {
		e.mu.Unlock()
		return nil, nil, os.ErrNotExist
	}
	e.pins[key]++
	e.mu.Unlock()
	release := sync.OnceFunc(func() {
		e.mu.Lock()
		e.pins[key]--
		if e.pins[key] == 0 {
			delete(e.pins, key)
		}
		e.mu.Unlock()
	})
	f, err := os.Open(filepath.Join(e.Config.Cache.Path, key+".webp"))
	if err != nil {
		release()
		return nil, nil, err
	}
	return f, release, nil
}

// Touch records an access to item's cover for LRU eviction, writing at most
// once per touchInterval per cover. Failures are ignored.
func (e *Engine) Touch(ctx context.Context, item library.Entry) {
	now := e.Queue.Now()
	e.Queue.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		_, err := tx.ExecContext(ctx, "UPDATE thumbnails SET accessed=? WHERE key=? AND accessed<?", now.UnixMilli(), e.Key(item), now.Add(-touchInterval).UnixMilli())
		return err
	})
}

// Bytes is the cover cache size recorded in the ledger.
func (e *Engine) Bytes(ctx context.Context) (int64, error) {
	var n int64
	err := e.Queue.Store.Reader.QueryRowContext(ctx, "SELECT value FROM counters WHERE name='thumbnail_bytes'").Scan(&n)
	return n, err
}

// Evict removes least recently used covers until reserve more bytes fit within
// cache.max_size and the cache filesystem keeps its free-space guard. Pinned
// covers are skipped; it fails if nothing else can be removed.
func (e *Engine) Evict(ctx context.Context, reserve int64) error {
	e.budget.Lock()
	defer e.budget.Unlock()
	if err := os.MkdirAll(e.Config.Cache.Path, 0700); err != nil {
		return err
	}
	for {
		n, err := e.Bytes(ctx)
		if err != nil {
			return err
		}
		total, free, err := mediafs.Space(e.Config.Cache.Path)
		if err != nil {
			return err
		}
		guard := min(total/20, maxFreeSpaceGuard)
		if n+reserve <= e.Config.Cache.MaxSize && free >= guard+reserve {
			return nil
		}
		rows, err := e.Queue.Store.Reader.QueryContext(ctx, "SELECT key,size FROM thumbnails WHERE state='ready' ORDER BY accessed,key LIMIT ?", storage.BatchSize)
		if err != nil {
			return err
		}
		type candidate struct {
			key  string
			size int64
		}
		batch := []candidate{}
		for rows.Next() {
			var c candidate
			if err = rows.Scan(&c.key, &c.size); err != nil {
				rows.Close()
				return err
			}
			batch = append(batch, c)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return err
		}
		removed := false
		for _, c := range batch {
			e.mu.Lock()
			busy := e.pins[c.key] > 0 || e.deleting[c.key]
			if !busy {
				e.deleting[c.key] = true
			}
			e.mu.Unlock()
			if busy {
				continue
			}
			err = os.Remove(filepath.Join(e.Config.Cache.Path, c.key+".webp"))
			if errors.Is(err, os.ErrNotExist) {
				err = nil
			}
			if err == nil {
				err = e.forget(ctx, c.key, c.size)
			}
			e.mu.Lock()
			delete(e.deleting, c.key)
			e.mu.Unlock()
			if err != nil {
				return err
			}
			removed = true
			break
		}
		if !removed {
			return errors.New("thumbnail cache has no reclaimable space")
		}
	}
}

// rotation returns the filters that turn an image clockwise by degrees.
func rotation(degrees int) string {
	switch degrees {
	case 90:
		return "transpose=clock,"
	case 180:
		return "hflip,vflip,"
	case 270:
		return "transpose=cclock,"
	}
	return ""
}

// Run generates one cover and records its outcome in Metrics.
func (e *Engine) Run(ctx context.Context, j jobs.Job) error {
	err := e.run(ctx, j, time.Now())
	if err != nil && ctx.Err() == nil && !errors.Is(err, library.ErrConflict) {
		e.Metrics.Failed.Add(1)
	}
	return err
}

func (e *Engine) run(ctx context.Context, j jobs.Job, start time.Time) error {
	item, err := e.Queue.Index.Media(ctx, j.MediaID)
	if err != nil || item.Version != j.Version {
		return library.ErrConflict
	}
	if j.Parameters != e.Parameters {
		return e.Queue.Finish(ctx, j, "stale", "thumbnail_parameters_changed")
	}
	if item.Kind == "video" && item.DurationMS <= 0 {
		return e.Queue.Finish(ctx, j, "failed", "unknown_duration")
	}
	if err = e.Evict(ctx, int64(e.Config.Cache.Parallel)*coverReserve); err != nil {
		return err
	}
	f, validate, err := e.Queue.Index.OpenMedia(ctx, item)
	if err != nil {
		return err
	}
	defer f.Close()
	tmp, err := os.CreateTemp(e.Config.Cache.Path, ".frame-*.webp")
	if err != nil {
		return err
	}
	temp := tmp.Name()
	tmp.Close()
	defer os.Remove(temp)
	timeout, cancel := context.WithTimeout(ctx, coverTimeout)
	defer cancel()
	args := []string{"-nostdin", "-hide_banner", "-loglevel", "error", "-threads", strconv.Itoa(e.Config.Cache.Threads)}
	filter := coverFilter
	var grid probe.Grid
	tiled := false
	if item.Kind == "video" {
		seconds := float64(item.DurationMS) / 1000 * e.Config.Cache.Percent / 100
		args = append(args, "-ss", strconv.FormatFloat(seconds, 'f', 3, 64))
	} else {
		// FFmpeg versions differ on applying EXIF/irot rotation to images, so
		// the probed rotation is applied explicitly, matching the size the
		// client lays out.
		args = append(args, "-noautorotate")
		filter = rotation(item.Rotation) + filter
		if probe.IsHEIF(item.Name) {
			grid, tiled = probe.TileGrid(ctx, f)
			if _, err = f.Seek(0, io.SeekStart); err != nil {
				return err
			}
		}
	}
	args = append(args, "-i", probe.ExtraFilePath())
	if tiled {
		args = append(args, "-filter_complex", grid.Filter()+","+filter+"[cover]", "-map", "[cover]")
	} else {
		args = append(args, "-map", "0:v:0", "-vf", filter)
	}
	args = append(args, "-frames:v", "1", "-filter_threads", "1", "-filter_complex_threads", "1", "-c:v", Encoder, "-threads:v", "1", "-quality", coverQuality, "-y", temp)
	cmd := exec.CommandContext(timeout, "ffmpeg", args...)
	cmd.ExtraFiles = []*os.File{f}
	var stderr probe.Diagnostics
	cmd.Stderr = &stderr
	if err = cmd.Run(); err != nil {
		return stderr.Wrap("ffmpeg", err)
	}
	if err = validate(); err != nil {
		return library.ErrConflict
	}
	stat, err := os.Stat(temp)
	if err != nil || stat.Size() <= 0 || stat.Size() > maxCoverSize {
		return errors.New("thumbnail output invalid")
	}
	if err = os.Chmod(temp, 0600); err != nil {
		return err
	}
	output, err := os.OpenFile(temp, os.O_RDWR, 0600)
	if err != nil {
		return err
	}
	err = output.Sync()
	output.Close()
	if err != nil {
		return err
	}
	key := e.Key(item)
	e.budget.Lock()
	if err = os.Rename(temp, filepath.Join(e.Config.Cache.Path, key+".webp")); err != nil {
		e.budget.Unlock()
		return err
	}
	err = e.Queue.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		var current string
		if err := tx.QueryRowContext(ctx, `SELECT e.version FROM entries e JOIN directories d ON d.id=e.directory_id WHERE e.media_id=? AND e.generation=d.generation`, item.ID).Scan(&current); err != nil {
			return err
		}
		if current != item.Version {
			return library.ErrConflict
		}
		var prior int64
		err := tx.QueryRowContext(ctx, "SELECT size FROM thumbnails WHERE key=?", key).Scan(&prior)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if _, err = tx.ExecContext(ctx, `INSERT INTO thumbnails(key,media_id,version,size,accessed) VALUES(?,?,?,?,?) ON CONFLICT(key) DO UPDATE SET size=excluded.size,accessed=excluded.accessed`, key, item.ID, item.Version, stat.Size(), e.Queue.Now().UnixMilli()); err != nil {
			return err
		}
		if _, err = tx.ExecContext(ctx, "UPDATE counters SET value=value+? WHERE name='thumbnail_bytes'", stat.Size()-prior); err != nil {
			return err
		}
		if _, err = tx.ExecContext(ctx, "UPDATE versions SET status='ready',error='' WHERE media_id=? AND version=?", item.ID, item.Version); err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, "UPDATE jobs SET state='done',lease=0 WHERE id=?", j.ID)
		return err
	})
	if err != nil {
		os.Remove(filepath.Join(e.Config.Cache.Path, key+".webp"))
		e.budget.Unlock()
		return err
	}
	e.budget.Unlock()
	elapsed := time.Since(start)
	e.Metrics.Generated.Add(1)
	e.Metrics.GenerateNS.Add(elapsed.Nanoseconds())
	slog.Debug("thumbnail_generated", "media_id", item.ID, "kind", item.Kind, "duration_ms", elapsed.Milliseconds())
	return e.Evict(ctx, 0)
}

// forget deletes a cover's ledger row and subtracts its size, once. The caller
// has already removed the file and holds budget.
func (e *Engine) forget(ctx context.Context, key string, size int64) error {
	return e.Queue.Store.Write(ctx, 0, func(tx *sql.Tx) error {
		r, err := tx.ExecContext(ctx, "DELETE FROM thumbnails WHERE key=?", key)
		if err != nil {
			return err
		}
		n, err := r.RowsAffected()
		if err != nil {
			return err
		}
		if n > 0 {
			_, err = tx.ExecContext(ctx, "UPDATE counters SET value=max(0,value-?) WHERE name='thumbnail_bytes'", size)
		}
		return err
	})
}
