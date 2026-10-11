package storage

import (
	"database/sql"
	"fmt"
	"path/filepath"
	"testing"
)

// BenchmarkBatchWrite isolates commit batching on the actual indexed schema.
// It updates a fixed 1024-row set so successive iterations never enlarge the
// database. Fixture creation is excluded; WAL/FULL and production Write remain
// enabled. This measures indexed SQL writes, not filesystem scanning or probes.
func BenchmarkBatchWrite(b *testing.B) {
	const rows = 1024
	for _, batch := range []int{1, 32, 128, 512} {
		b.Run(fmt.Sprintf("batch-%d", batch), func(b *testing.B) {
			s, err := Open(b.Context(), filepath.Join(b.TempDir(), "index.sqlite"))
			if err != nil {
				b.Fatal(err)
			}
			b.Cleanup(func() {
				if err := s.Close(); err != nil {
					b.Error(err)
				}
			})
			ids := make([]string, rows)
			for index := range ids {
				ids[index] = fmt.Sprintf("fixture-%04d", index)
			}
			err = s.Write(b.Context(), 0, func(tx *sql.Tx) error {
				for _, statement := range []string{
					"INSERT INTO libraries(id,root,name) VALUES('fixture','/synthetic','fixture')",
					"INSERT INTO directories(id,library_id,relative,name,generation) VALUES(1,'fixture','','fixture',1)",
				} {
					if _, err := tx.ExecContext(b.Context(), statement); err != nil {
						return err
					}
				}
				for _, id := range ids {
					if _, err := tx.ExecContext(b.Context(), "INSERT INTO media(id,directory_id,name) VALUES(?,1,?)", id, id); err != nil {
						return err
					}
					if _, err := tx.ExecContext(b.Context(), "INSERT INTO versions(media_id,version,fingerprint,size,mtime,kind,effective_time) VALUES(?,'v','synthetic',7,0,'video',0)", id); err != nil {
						return err
					}
					if _, err := tx.ExecContext(b.Context(), "INSERT INTO entries(directory_id,generation,media_id,version,name,kind,effective_time,mtime) VALUES(1,1,?,'v',?,'video',0,0)", id, id); err != nil {
						return err
					}
				}
				return nil
			})
			if err != nil {
				b.Fatal(err)
			}
			var mode string
			var synchronous int
			if err := s.Writer.QueryRowContext(b.Context(), "PRAGMA journal_mode").Scan(&mode); err != nil || mode != "wal" {
				b.Fatalf("WAL required: %s, %v", mode, err)
			}
			if err := s.Writer.QueryRowContext(b.Context(), "PRAGMA synchronous").Scan(&synchronous); err != nil || synchronous != 2 {
				b.Fatalf("FULL required: %d, %v", synchronous, err)
			}
			s.Metrics.Commits.Store(0)
			s.Metrics.MaxTransactionNS.Store(0)
			b.ReportAllocs()
			iterations := 0
			for b.Loop() {
				for first := 0; first < rows; first += batch {
					err := s.Write(b.Context(), 0, func(tx *sql.Tx) error {
						stmt, err := tx.PrepareContext(b.Context(), "UPDATE entries SET mtime=mtime+1,effective_time=effective_time+1 WHERE directory_id=1 AND generation=1 AND media_id=?")
						if err != nil {
							return err
						}
						defer stmt.Close()
						for _, id := range ids[first : first+batch] {
							if _, err := stmt.ExecContext(b.Context(), id); err != nil {
								return err
							}
						}
						return nil
					})
					if err != nil {
						b.Fatal(err)
					}
				}
				iterations++
			}
			var count, total int
			if err := s.Reader.QueryRowContext(b.Context(), "SELECT count(*),sum(mtime) FROM entries").Scan(&count, &total); err != nil || count != rows || total != rows*iterations {
				b.Fatalf("lost writes: count=%d sum=%d iterations=%d err=%v", count, total, iterations, err)
			}
			if s.Metrics.Commits.Load() != int64(iterations*rows/batch) {
				b.Fatal("unexpected commit count")
			}
			b.ReportMetric(float64(rows*iterations)/b.Elapsed().Seconds(), "rows/s")
			b.ReportMetric(float64(rows/batch), "commits/op")
			b.ReportMetric(float64(s.Metrics.MaxTransactionNS.Load())/1e6, "max-tx-ms")
		})
	}
}
