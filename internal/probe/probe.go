package probe

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"time"
	"vvideo/internal/jobs"
	"vvideo/internal/library"
	"vvideo/internal/mediafs"
)

type Result struct {
	Width, Height, Rotation     int
	DurationMS, EffectiveTime   int64
	CapturedRaw, CapturedSource string
}

// boundedBuffer rejects tool output beyond Limit, failing the command.
type boundedBuffer struct {
	bytes.Buffer
	Limit int
}

func (b *boundedBuffer) Write(p []byte) (int, error) {
	if b.Len()+len(p) > b.Limit {
		return 0, errors.New("tool output exceeds bound")
	}
	return b.Buffer.Write(p)
}

// Diagnostics keeps the start of a tool's stderr for error messages. It never
// fails a write, so verbose diagnostics cannot turn success into failure.
type Diagnostics struct {
	buf bytes.Buffer
}

const diagnosticsLimit = 2048

func (d *Diagnostics) Write(p []byte) (int, error) {
	if room := diagnosticsLimit - d.buf.Len(); room > 0 {
		d.buf.Write(p[:min(len(p), room)])
	}
	return len(p), nil
}

// Wrap annotates a tool failure with its captured stderr, if any.
func (d *Diagnostics) Wrap(tool string, err error) error {
	if text := strings.TrimSpace(d.buf.String()); text != "" {
		return fmt.Errorf("%s: %w: %s", tool, err, text)
	}
	return fmt.Errorf("%s: %w", tool, err)
}

// ExtraFilePath names, inside a child process, the first descriptor passed via
// exec.Cmd.ExtraFiles. Tools read media through it so the source path is never
// reopened by name.
func ExtraFilePath() string {
	if runtime.GOOS == "darwin" {
		return "/dev/fd/3"
	}
	return "/proc/self/fd/3"
}

func Run(ctx context.Context, f *os.File, e library.Entry) (Result, error) {
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "ffprobe", "-v", "error", "-show_entries", "format=duration:format_tags=creation_time:stream=codec_type,width,height,duration:stream_tags=creation_time:stream_side_data=rotation", "-of", "json", ExtraFilePath())
	cmd.ExtraFiles = []*os.File{f}
	out := &boundedBuffer{Limit: 1 << 20}
	var stderr Diagnostics
	cmd.Stdout = out
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return Result{}, stderr.Wrap("ffprobe", err)
	}
	var data struct {
		Streams []struct {
			CodecType string `json:"codec_type"`
			Width     int    `json:"width"`
			Height    int    `json:"height"`
			Duration  string `json:"duration"`
			Tags      struct {
				CreationTime string `json:"creation_time"`
			} `json:"tags"`
			SideData []struct {
				Rotation int `json:"rotation"`
			} `json:"side_data_list"`
		} `json:"streams"`
		Format struct {
			Duration string `json:"duration"`
			Tags     struct {
				CreationTime string `json:"creation_time"`
			} `json:"tags"`
		} `json:"format"`
	}
	if err := json.Unmarshal(out.Bytes(), &data); err != nil {
		return Result{}, fmt.Errorf("ffprobe: invalid JSON output: %w", err)
	}
	r := Result{Width: 1, Height: 1, EffectiveTime: e.Mtime, CapturedSource: "mtime"}
	r.CapturedRaw = data.Format.Tags.CreationTime
	for _, s := range data.Streams {
		if s.CodecType == "video" {
			r.Width = max(1, s.Width)
			r.Height = max(1, s.Height)
			if len(s.SideData) > 0 {
				r.Rotation = s.SideData[0].Rotation
			}
			if r.CapturedRaw == "" {
				r.CapturedRaw = s.Tags.CreationTime
			}
			if data.Format.Duration == "" {
				data.Format.Duration = s.Duration
			}
			break
		}
	}
	duration, err := strconv.ParseFloat(data.Format.Duration, 64)
	if err == nil && duration > 0 && duration < 315360000 {
		r.DurationMS = int64(duration * 1000)
	}
	if r.CapturedRaw != "" {
		if t, err := time.Parse(time.RFC3339Nano, r.CapturedRaw); err == nil && plausible(t) {
			r.EffectiveTime = t.UnixNano()
			r.CapturedSource = "container_creation_time"
		}
	}
	if e.Kind == "image" {
		if _, err = f.Seek(0, io.SeekStart); err != nil {
			return r, err
		}
		raw, offset, rotation := Exif(f)
		if raw != "" {
			r.CapturedRaw = raw
			r.EffectiveTime, r.CapturedSource = CapturedTime(raw, offset, e.Mtime)
		}
		if rotation != 0 {
			r.Rotation = rotation
		}
	}
	return r, nil
}

type Worker struct {
	Queue      *jobs.Queue
	Parameters string
	Now        func() time.Time
	Tool       func(context.Context, *os.File, library.Entry) (Result, error)
	Rescan     func()
}

func (w *Worker) Run(ctx context.Context, j jobs.Job) error {
	e, err := w.Queue.Index.Media(ctx, j.MediaID)
	if err != nil || e.Version != j.Version {
		return library.ErrConflict
	}
	f, validate, err := w.Queue.Index.OpenMedia(ctx, e)
	if err != nil {
		if w.Rescan != nil {
			w.Rescan()
		}
		return err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return err
	}
	fp := mediafs.Fingerprint(info)
	if w.Now().UnixMilli()-j.FirstObserved < 60_000 || fp != j.Fingerprint {
		return w.Queue.Delay(ctx, j, fp)
	}
	tool := w.Tool
	if tool == nil {
		tool = Run
	}
	r, err := tool(ctx, f, e)
	if err != nil {
		return err
	}
	if err = validate(); err != nil {
		if w.Rescan != nil {
			w.Rescan()
		}
		return library.ErrConflict
	}
	return w.Queue.Store.Write(ctx, 1, func(tx *sql.Tx) error {
		var current string
		if err := tx.QueryRowContext(ctx, `SELECT e.version FROM entries e JOIN directories d ON d.id=e.directory_id WHERE e.media_id=? AND e.generation=d.generation`, e.ID).Scan(&current); err != nil {
			return err
		}
		if current != e.Version {
			return library.ErrConflict
		}
		if _, err := tx.ExecContext(ctx, `UPDATE versions SET width=?,height=?,rotation=?,duration_ms=?,captured_raw=?,captured_source=?,effective_time=?,status='probe_ready' WHERE media_id=? AND version=?`, r.Width, r.Height, r.Rotation, r.DurationMS, r.CapturedRaw, r.CapturedSource, r.EffectiveTime, e.ID, e.Version); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, "UPDATE entries SET effective_time=? WHERE media_id=? AND version=?", r.EffectiveTime, e.ID, e.Version); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, "UPDATE directories SET revision=revision+1 WHERE id=?", e.DirectoryID); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, "UPDATE jobs SET state='done',lease=0 WHERE id=?", j.ID); err != nil {
			return err
		}
		if e.Kind == "video" && r.DurationMS <= 0 {
			_, err := tx.ExecContext(ctx, "UPDATE versions SET status='unsupported',error='unknown_duration' WHERE media_id=? AND version=?", e.ID, e.Version)
			return err
		}
		_, err := tx.ExecContext(ctx, `INSERT INTO jobs(media_id,version,kind,parameters,due) VALUES(?,?,'thumbnail',?,?) ON CONFLICT(media_id,version,kind,parameters) DO NOTHING`, e.ID, e.Version, w.Parameters, w.Now().UnixMilli())
		return err
	})
}
func Parameters(percent float64) string {
	return fmt.Sprintf("percent=%s;width=640;quality=80;format=webp", strconv.FormatFloat(percent, 'g', -1, 64))
}

// CapturedTime parses an EXIF original time only with its explicit offset; it
// never infers a timezone from a server or phone setting. Missing, unparsable or
// implausible times fall back to the file modification time.
func CapturedTime(raw, offset string, mtime int64) (int64, string) {
	if strings.TrimSpace(offset) == "" {
		return mtime, "mtime"
	}
	t, err := time.Parse("2006:01:02 15:04:05-07:00", raw+offset)
	if err != nil || !plausible(t) {
		return mtime, "mtime"
	}
	return t.UnixNano(), "exif_datetime_original"
}

func plausible(t time.Time) bool { return t.Year() >= 1970 && t.Year() <= 2100 }
