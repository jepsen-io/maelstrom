package maelstrom_test

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"reflect"
	"strings"
	"testing"
	"time"

	maelstrom "github.com/jepsen-io/maelstrom/demo/go"
)

func TestNode_Run(t *testing.T) {
	t.Run("ErrMalformedInputJSON", func(t *testing.T) {
		var stdout bytes.Buffer
		n := maelstrom.NewNode()
		n.Stdin = strings.NewReader("\n")
		n.Stdout = &stdout
		if err := n.Run(); err == nil || err.Error() != `unmarshal message: unexpected end of JSON input` {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("ErrMissingHandler", func(t *testing.T) {
		var stdout bytes.Buffer
		n := maelstrom.NewNode()
		n.Stdin = strings.NewReader(`{"dest":"n1", "body":{"type":"echo", "msg_id":1}}` + "\n")
		n.Stdout = &stdout
		if err := n.Run(); err == nil || err.Error() != `No handler for {"dest":"n1", "body":{"type":"echo", "msg_id":1}}` {
			t.Fatalf("unexpected error: %v", err)
		}
	})

	t.Run("ReturnRPCError", func(t *testing.T) {
		var stdout bytes.Buffer
		n := maelstrom.NewNode()
		n.Stdin = strings.NewReader(`{"dest":"n1", "body":{"type":"foo", "msg_id":1000}}` + "\n")
		n.Stdout = &stdout
		n.Handle("foo", func(msg maelstrom.Message) error {
			return maelstrom.NewRPCError(maelstrom.NotSupported, "bad call")
		})
		if err := n.Run(); err != nil {
			t.Fatal(err)
		}
		if got, want := stdout.String(), `{"body":{"code":10,"in_reply_to":1000,"text":"bad call","type":"error"}}`+"\n"; got != want {
			t.Fatalf("stdout=%s, want %s", got, want)
		}
	})

	t.Run("ReturnNonRPCError", func(t *testing.T) {
		var stdout bytes.Buffer
		n := maelstrom.NewNode()
		n.Stdin = strings.NewReader(`{"dest":"n1", "body":{"type":"foo", "msg_id":1000}}` + "\n")
		n.Stdout = &stdout
		n.Handle("foo", func(msg maelstrom.Message) error {
			return fmt.Errorf("bad call")
		})
		if err := n.Run(); err != nil {
			t.Fatal(err)
		}
		if got, want := stdout.String(), `{"body":{"code":13,"in_reply_to":1000,"text":"bad call","type":"error"}}`+"\n"; got != want {
			t.Fatalf("stdout=%s, want %s", got, want)
		}
	})
}

// Ensure a node can handle the "init" message.
func TestNode_Run_Init(t *testing.T) {
	n, stdin, stdout := newNode(t)

	initialized := make(chan struct{})
	n.Handle("init", func(msg maelstrom.Message) error {
		initialized <- struct{}{}
		return nil
	})

	// Send "init" message to node.
	if _, err := stdin.Write([]byte(`{"body":{"type":"init", "msg_id":1, "node_id":"n3", "node_ids":["n1", "n2", "n3"]}}` + "\n")); err != nil {
		t.Fatal(err)
	}

	// Ensure node extracts the ID & cluster membership.
	select {
	case <-initialized:
		if got, want := n.ID(), "n3"; got != want {
			t.Fatalf("node_id=%q, want %q", got, want)
		}
		if got, want := n.NodeIDs(), []string{"n1", "n2", "n3"}; !reflect.DeepEqual(got, want) {
			t.Fatalf("node_ids=%q, want %q", got, want)
		}
	}

	// Ensure a correct response was sent back to the network.
	if line, err := stdout.ReadString('\n'); err != nil {
		t.Fatal(err)
	} else if got, want := line, `{"src":"n3","body":{"in_reply_to":1,"type":"init_ok"}}`+"\n"; got != want {
		t.Fatalf("response=%s, want %s", got, want)
	}
}

// Ensure a node can act as an echo server.
func TestNode_Run_Echo(t *testing.T) {
	n, stdin, stdout := newNode(t)

	n.Handle("echo", func(msg maelstrom.Message) error {
		var body map[string]any
		if err := json.Unmarshal(msg.Body, &body); err != nil {
			return err
		}
		body["type"] = "echo_ok"

		return n.Reply(msg, body)
	})

	// Initialize node.
	initNode(t, n, "n1", []string{"n1"}, stdin, stdout)

	// Send echo message.
	if _, err := stdin.Write([]byte(`{"dest":"n1", "body":{"type":"echo", "msg_id":2}}` + "\n")); err != nil {
		t.Fatal(err)
	}

	// Ensure response is echo'd back.
	if line, err := stdout.ReadString('\n'); err != nil {
		t.Fatal(err)
	} else if got, want := line, `{"src":"n1","body":{"in_reply_to":2,"msg_id":2,"type":"echo_ok"}}`+"\n"; got != want {
		t.Fatalf("response=%s, want %s", got, want)
	}
}

// Ensure a duplicate handler causes a panic.
func TestNode_Handle(t *testing.T) {
	t.Run("ErrDuplicate", func(t *testing.T) {
		n, _, _ := newNode(t)
		n.Handle("foo", func(msg maelstrom.Message) error { return nil })

		var r any
		func() {
			defer func() {
				r = recover()
			}()
			n.Handle("foo", func(msg maelstrom.Message) error { return nil })
		}()

		if got, want := r, `duplicate message handler for "foo" message type`; got != want {
			t.Fatalf("recover=%s, want %s", got, want)
		}
	})
}

// Ensure node can handle a request/response RPC call.
func TestNode_RPC(t *testing.T) {
	t.Run("OK", func(t *testing.T) {
		n, stdin, stdout := newNode(t)
		initNode(t, n, "n1", []string{"n1", "n2"}, stdin, stdout)

		// Send RPC call.
		respCh := make(chan maelstrom.Message)
		errorCh := make(chan error)
		go func() {
			if err := n.RPC("n2", map[string]any{"type": "foo", "bar": "baz"}, func(msg maelstrom.Message) error {
				respCh <- msg
				return nil
			}); err != nil {
				errorCh <- err
			}
		}()

		// Ensure RPC request is received by the network.
		if line, err := stdout.ReadString('\n'); err != nil {
			t.Fatal(err)
		} else if got, want := line, `{"src":"n1","dest":"n2","body":{"bar":"baz","msg_id":1,"type":"foo"}}`+"\n"; got != want {
			t.Fatalf("response=%s, want %s", got, want)
		}

		// Write response message back to node.
		if _, err := stdin.Write([]byte(`{"src":"n2", "dest":"n1", "body":{"type":"foo_ok", "msg_id":2, "in_reply_to":1}}` + "\n")); err != nil {
			t.Fatal(err)
		}

		// Ensure the callback was handled.
		select {
		case msg := <-respCh:
			if got, want := msg.Src, "n2"; got != want {
				t.Fatalf("Src=%s, want %s", got, want)
			}
			if got, want := msg.Dest, "n1"; got != want {
				t.Fatalf("Dest=%s, want %s", got, want)
			}
			if got, want := string(msg.Body), `{"type":"foo_ok", "msg_id":2, "in_reply_to":1}`; got != want {
				t.Fatalf("Body=%s, want %s", got, want)
			}
		case err := <-errorCh:
			t.Fatal(err)
		case <-time.After(5 * time.Second):
			t.Fatal("timeout waiting for RPC response")
		}
	})

	t.Run("SkipMissingCallback", func(t *testing.T) {
		n, stdin, stdout := newNode(t)
		initNode(t, n, "n1", []string{"n1", "n2"}, stdin, stdout)
		if _, err := stdin.Write([]byte(`{"src":"n2", "dest":"n1", "body":{"type":"foo_ok", "msg_id":2, "in_reply_to":1000}}` + "\n")); err != nil {
			t.Fatal(err)
		}
	})
}

// Ensure node can handle a synchronous request/response RPC call.
func TestNode_SyncRPC(t *testing.T) {
	t.Run("OK", func(t *testing.T) {
		n, stdin, stdout := newNode(t)
		initNode(t, n, "n1", []string{"n1", "n2"}, stdin, stdout)

		// Send RPC call.
		respCh := make(chan maelstrom.Message)
		errorCh := make(chan error)
		go func() {
			resp, err := n.SyncRPC(context.Background(), "n2", map[string]any{"type": "foo", "bar": "baz"})
			if err != nil {
				errorCh <- err
			} else {
				respCh <- resp
			}
		}()

		// Ensure RPC request is received by the network.
		if line, err := stdout.ReadString('\n'); err != nil {
			t.Fatal(err)
		} else if got, want := line, `{"src":"n1","dest":"n2","body":{"bar":"baz","msg_id":1,"type":"foo"}}`+"\n"; got != want {
			t.Fatalf("response=%s, want %s", got, want)
		}

		// Write response message back to node.
		if _, err := stdin.Write([]byte(`{"src":"n2", "dest":"n1", "body":{"type":"foo_ok", "msg_id":2, "in_reply_to":1}}` + "\n")); err != nil {
			t.Fatal(err)
		}

		// Ensure the response was received.
		select {
		case msg := <-respCh:
			if got, want := msg.Src, "n2"; got != want {
				t.Fatalf("Src=%s, want %s", got, want)
			}
			if got, want := msg.Dest, "n1"; got != want {
				t.Fatalf("Dest=%s, want %s", got, want)
			}
			if got, want := string(msg.Body), `{"type":"foo_ok", "msg_id":2, "in_reply_to":1}`; got != want {
				t.Fatalf("Body=%s, want %s", got, want)
			}
		case err := <-errorCh:
			t.Fatal(err)
		case <-time.After(5 * time.Second):
			t.Fatal("timeout waiting for RPC response")
		}
	})

	t.Run("ErrContextTimeout", func(t *testing.T) {
		n, stdin, stdout := newNode(t)
		initNode(t, n, "n1", []string{"n1", "n2"}, stdin, stdout)

		// Send RPC call.
		errorCh := make(chan error)
		go func() {
			ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
			defer cancel()

			_, err := n.SyncRPC(ctx, "n2", map[string]any{"type": "foo", "bar": "baz"})
			errorCh <- err
		}()

		// Ensure RPC request is received by the network. Do not write a response.
		if line, err := stdout.ReadString('\n'); err != nil {
			t.Fatal(err)
		} else if got, want := line, `{"src":"n1","dest":"n2","body":{"bar":"baz","msg_id":1,"type":"foo"}}`+"\n"; got != want {
			t.Fatalf("response=%s, want %s", got, want)
		}

		// Ensure the response was received.
		select {
		case err := <-errorCh:
			if err == nil || err.Error() != `context deadline exceeded` {
				t.Fatalf("unexpected error: %s", err)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("timeout waiting for RPC response")
		}
	})

	t.Run("RPCError", func(t *testing.T) {
		n, stdin, stdout := newNode(t)
		initNode(t, n, "n1", []string{"n1", "n2"}, stdin, stdout)

		// Send RPC call.
		errorCh := make(chan error)
		go func() {
			_, err := n.SyncRPC(context.Background(), "n2", map[string]any{"type": "foo", "bar": "baz"})
			errorCh <- err
		}()

		// Ensure RPC request is received by the network.
		if line, err := stdout.ReadString('\n'); err != nil {
			t.Fatal(err)
		} else if got, want := line, `{"src":"n1","dest":"n2","body":{"bar":"baz","msg_id":1,"type":"foo"}}`+"\n"; got != want {
			t.Fatalf("response=%s, want %s", got, want)
		}

		// Write error response back to node.
		if _, err := stdin.Write([]byte(`{"src":"n2", "dest":"n1", "body":{"type":"foo_ok", "msg_id":2, "in_reply_to":1, "code":20, "text":"key does not exist"}}` + "\n")); err != nil {
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

// newNode initializes a test node and returns streams to read/write messages.
func newNode(tb testing.TB) (node *maelstrom.Node, stdin io.Writer, stdout *bufio.Reader) {
	inr, inw := io.Pipe()
	outr, outw := io.Pipe()

	// Initialize node and set up pipes so the test can read & write.
	n := maelstrom.NewNode()
	n.Stdin = inr
	n.Stdout = outw

	// Start the message loop.
	done := make(chan error)
	go func() {
		if err := n.Run(); err != nil {
			tb.Errorf("run error: %s", err)
		}
		close(done)
	}()

	// Ensure node stops by the end of the test.
	tb.Cleanup(func() {
		if err := inw.Close(); err != nil {
			tb.Fatalf("closing stdin: %s", err)
		}

		select {
		case <-time.After(5 * time.Second):
			tb.Fatalf("timeout waiting for node to stop")
		case <-done:
		}
	})

	return n, inw, bufio.NewReader(outr)
}

func initNode(tb testing.TB, n *maelstrom.Node, id string, nodeIDs []string, stdin io.Writer, stdout *bufio.Reader) {
	tb.Helper()

	nodeIDsStr := `"` + strings.Join(nodeIDs, `","`) + `"`
	if _, err := stdin.Write([]byte(fmt.Sprintf(`{"body":{"type":"init", "msg_id":1, "node_id":"%s", "node_ids":[%s]}}`+"\n", id, nodeIDsStr))); err != nil {
		tb.Fatal(err)
	}

	// Read & verify
	if line, err := stdout.ReadString('\n'); err != nil {
		tb.Fatal(err)
	} else if got, want := line, fmt.Sprintf(`{"src":"%s","body":{"in_reply_to":1,"type":"init_ok"}}`+"\n", id); got != want {
		tb.Fatalf("init_ok=%s, want %s", got, want)
	}
}
