package maelstrom

import (
	"encoding/json"
	"fmt"
)

// RPC error code constants.
const (
	Timeout                = 0
	NotSupported           = 10
	TemporarilyUnavailable = 11
	MalformedRequest       = 12
	Crash                  = 13
	Abort                  = 14
	KeyDoesNotExist        = 20
	KeyAlreadyExists       = 21
	PreconditionFailed     = 22
	TxnConflict            = 30
)

// ErrorCodeText returns the text representation of an error code.
func ErrorCodeText(code int) string {
	switch code {
	case Timeout:
		return "Timeout"
	case NotSupported:
		return "NotSupported"
	case TemporarilyUnavailable:
		return "TemporarilyUnavailable"
	case MalformedRequest:
		return "MalformedRequest"
	case Crash:
		return "Crash"
	case Abort:
		return "Abort"
	case KeyDoesNotExist:
		return "KeyDoesNotExist"
	case KeyAlreadyExists:
		return "KeyAlreadyExists"
	case PreconditionFailed:
		return "PreconditionFailed"
	case TxnConflict:
		return "TxnConflict"
	default:
		return fmt.Sprintf("ErrorCode<%d>", code)
	}
}

// ErrorCode returns the error code from err. Returns -1 if err is not an *RPCError.
func ErrorCode(err error) int {
	switch err := err.(type) {
	case *RPCError:
		return err.Code
	default:
		return -1
	}
}

// RPCError represents a Maelstrom RPC error.
type RPCError struct {
	Code int
	Text string
}

// NewRPCError returns a new instance of RPCError.
func NewRPCError(code int, text string) *RPCError {
	return &RPCError{
		Code: code,
		Text: text,
	}
}

// Error returns a string-formatted error message.
func (e *RPCError) Error() string {
	return fmt.Sprintf("RPCError(%s, %q)", ErrorCodeText(e.Code), e.Text)
}

// MarshalJSON marshals the error into JSON format.
func (e *RPCError) MarshalJSON() ([]byte, error) {
	return json.Marshal(rpcErrorJSON{
		Type: "error",
		Code: e.Code,
		Text: e.Text,
	})
}

// rpcErrorJSON is a struct for marshaling an RPCError to JSON.
type rpcErrorJSON struct {
	Type string `json:"type,omitempty"`
	Code int    `json:"code,omitempty"`
	Text string `json:"text,omitempty"`
}
