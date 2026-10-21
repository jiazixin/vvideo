package storage

import "database/sql"

// Collect reads every row of a query with scan and closes the rows. Pass the
// query's results straight in: a query error is returned unchanged. Callers
// bound the result with LIMIT, typically BatchSize.
func Collect[T any](rows *sql.Rows, err error, scan func(*sql.Rows) (T, error)) ([]T, error) {
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []T{}
	for rows.Next() {
		v, err := scan(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}
