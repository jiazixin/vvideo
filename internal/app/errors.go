package app

import (
	"database/sql"
	"errors"
	"io/fs"
	"net/http"

	"vvideo/internal/library"
)

// responseError is limited to the HTTP boundary. Domain packages return their
// own errors and never need to know the API's status codes.
type responseError struct {
	status  int
	code    string
	message string
	fields  []fieldFailure
}

func (e responseError) Error() string { return e.message }

type fieldFailure struct {
	Path   string `json:"path"`
	Reason string `json:"reason"`
}

var (
	unauthenticated      = responseError{status: http.StatusUnauthorized, code: "unauthenticated", message: "missing or invalid bearer token"}
	mediaBusy            = responseError{status: http.StatusServiceUnavailable, code: "media_busy", message: "too many concurrent media requests"}
	thumbnailPending     = responseError{status: http.StatusServiceUnavailable, code: "thumbnail_pending", message: "thumbnail not ready yet"}
	thumbnailUnavailable = responseError{status: http.StatusNotFound, code: "thumbnail_unavailable", message: "thumbnail cannot be generated for this version"}
)

func invalidParameter(path, reason string) error {
	return responseError{status: http.StatusBadRequest, code: "invalid_argument", message: "invalid request parameter", fields: []fieldFailure{{Path: path, Reason: reason}}}
}

func routerFailure(status int) error {
	switch status {
	case http.StatusNotFound:
		return responseError{status: status, code: "not_found", message: "resource or route not found"}
	case http.StatusMethodNotAllowed:
		return responseError{status: status, code: "method_not_allowed", message: "method not allowed"}
	case http.StatusPreconditionFailed:
		return responseError{status: status, code: "precondition_failed", message: "request precondition failed"}
	case http.StatusRequestedRangeNotSatisfiable:
		return responseError{status: status, code: "range_not_satisfiable", message: "requested byte range is not satisfiable"}
	default:
		return errors.New("unexpected HTTP handler failure")
	}
}

// writeError is the sole API error writer, including original-byte responses.
// Internal failures are logged with the request ID, never returned as details.
func writeError(w http.ResponseWriter, r *http.Request, err error) {
	if r.Context().Err() != nil {
		return // A disconnected client cannot receive a response.
	}
	failure, known := errors.AsType[responseError](err)
	if !known {
		switch {
		case errors.Is(err, library.ErrInvalid):
			failure = responseError{status: http.StatusBadRequest, code: "invalid_argument", message: "invalid parameter, cursor, sort or page limit", fields: []fieldFailure{{Path: "request", Reason: "invalid_value"}}}
		case errors.Is(err, library.ErrConflict):
			failure = responseError{status: http.StatusConflict, code: "revision_changed", message: "the directory or file version changed"}
		case errors.Is(err, library.ErrUnavailable), errors.Is(err, sql.ErrNoRows), errors.Is(err, fs.ErrNotExist):
			failure = responseError{status: http.StatusNotFound, code: "not_found", message: "resource or route not found"}
		default:
			failure = responseError{status: http.StatusInternalServerError, code: "internal", message: "internal error"}
			requestLogger(r).Error("request_failed", "route", r.Pattern, "error", err)
		}
	}
	type details struct {
		Fields []fieldFailure `json:"fields"`
	}
	type errorBody struct {
		Code    string   `json:"code"`
		Message string   `json:"message"`
		Details *details `json:"details,omitzero"`
	}
	body := errorBody{Code: failure.code, Message: failure.message}
	if len(failure.fields) != 0 {
		body.Details = &details{Fields: failure.fields}
	}
	writeJSON(w, failure.status, struct {
		Error errorBody `json:"error"`
	}{Error: body})
}
