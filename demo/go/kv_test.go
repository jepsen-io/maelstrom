package maelstrom_test

import (
	"context"
	"errors"
	"testing"
	"time"

	maelstrom "github.com/jepsen-io/maelstrom/demo/go"
)

func TestKVReadStruct(t *testing.T) {
	type testPayload struct {
		Counter int
	}
	t.Run("OK", func(t *testing.T) {
		n, stdin, stdout := newNode(t)
		kv := maelstrom.NewSeqKV(n)
		initNode(t, n, "n1", []string{"n1"}, stdin, stdout)

		respCh := make(chan testPayload)
		errorCh := make(chan error)
		go func() {
			var p testPayload
			err := kv.ReadInto(context.Background(), "foo", &p)
			if err != nil {
				errorCh <- err
				return
			}
			respCh <- p
		}()

		// Ensure RPC request is received by the network.
		if line, err := stdout.ReadString('\n'); err != nil {
			t.Fatal(err)
		} else if got, want := line, `{"src":"n1","dest":"seq-kv","body":{"key":"foo","msg_id":1,"type":"read"}}`+"\n"; got != want {
			t.Fatalf("response=%s, want %s", got, want)
		}

		// Write response message back to node.
		if _, err := stdin.Write([]byte(`{"src":"seq-kv","dest":"n1","body":{"type":"read_ok","value":{"Counter":13},"msg_id":2,"in_reply_to":1}}` + "\n")); err != nil {
			t.Fatal(err)
		}

		select {
		case p := <-respCh:
			if got, want := p.Counter, 13; got != want {
				t.Fatalf("counter=%d, want %d", got, want)
			}
		case err := <-errorCh:
			t.Fatal(err)
		case <-time.After(5 * time.Second):
			t.Fatal("timeout waiting for RPC response")
		}
	})
	t.Run("RPCError", func(t *testing.T) {
		n, stdin, stdout := newNode(t)
		kv := maelstrom.NewSeqKV(n)
		initNode(t, n, "n1", []string{"n1"}, stdin, stdout)

		errorCh := make(chan error)
		go func() {
			err := kv.ReadInto(context.Background(), "foo", nil)
			if err != nil {
				errorCh <- err
				return
			}
		}()

		// Ensure RPC request is received by the network.
		if line, err := stdout.ReadString('\n'); err != nil {
			t.Fatal(err)
		} else if got, want := line, `{"src":"n1","dest":"seq-kv","body":{"key":"foo","msg_id":1,"type":"read"}}`+"\n"; got != want {
			t.Fatalf("response=%s, want %s", got, want)
		}

		// Write response message back to node.
		if _, err := stdin.Write([]byte(`{"src":"seq-kv", "dest":"n1","body":{"type":"read_ok","code":20,"text":"key does not exist","msg_id":2,"in_reply_to":1}}` + "\n")); err != nil {
			t.Fatal(err)
		}

		// Ensure the response was received.
		select {
		case err := <-errorCh:
			var rpcError *maelstrom.RPCError
			if !errors.As(err, &rpcError) {
				t.Fatalf("unexpected error type: %#v", err)
			} else if got, want := rpcError.Code, 20; got != want {
				t.Fatalf("code=%v, want %v", got, want)
			} else if got, want := rpcError.Text, "key does not exist"; got != want {
				t.Fatalf("text=%v, want %v", got, want)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("timeout waiting for RPC response")
		}
	})
}
