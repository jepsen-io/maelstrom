package maelstrom_test

import (
	"testing"

	maelstrom "github.com/jepsen-io/maelstrom/demo/go"
)

func TestErrorCodeText(t *testing.T) {
	for _, tt := range []struct {
		code int
		text string
	}{
		{maelstrom.Timeout, "Timeout"},
		{maelstrom.NotSupported, "NotSupported"},
		{maelstrom.TemporarilyUnavailable, "TemporarilyUnavailable"},
		{maelstrom.MalformedRequest, "MalformedRequest"},
		{maelstrom.Crash, "Crash"},
		{maelstrom.Abort, "Abort"},
		{maelstrom.KeyDoesNotExist, "KeyDoesNotExist"},
		{maelstrom.KeyAlreadyExists, "KeyAlreadyExists"},
		{maelstrom.PreconditionFailed, "PreconditionFailed"},
		{maelstrom.TxnConflict, "TxnConflict"},
		{1000, "ErrorCode<1000>"},
	} {
		if got, want := maelstrom.ErrorCodeText(tt.code), tt.text; got != want {
			t.Errorf("code %d=%s, want %s", tt.code, got, want)
		}
	}
}

func TestRPCError_Error(t *testing.T) {
	if got, want := maelstrom.NewRPCError(maelstrom.Crash, "foo").Error(), `RPCError(Crash, "foo")`; got != want {
		t.Fatalf("error=%s, want %s", got, want)
	}
}
