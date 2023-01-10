package maelstrom

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"sync"
)

// Node represents a single node in the network.
type Node struct {
	mu sync.Mutex
	wg sync.WaitGroup

	id        string
	nodeIDs   []string
	nextMsgID int

	handlers  map[string]HandlerFunc
	callbacks map[int]HandlerFunc

	// Stdin is for reading messages in from the Maelstrom network.
	Stdin io.Reader

	// Stdin is for writing messages out to the Maelstrom network.
	Stdout io.Writer
}

// NewNode returns a new instance of Node connected to STDIN/STDOUT.
func NewNode() *Node {
	return &Node{
		handlers:  make(map[string]HandlerFunc),
		callbacks: make(map[int]HandlerFunc),

		Stdin:  os.Stdin,
		Stdout: os.Stdout,
	}
}

// ID returns the identifier for this node.
// Only valid after "init" message has been received.
func (n *Node) ID() string {
	return n.id
}

// NodeIDs returns a list of all node IDs in the cluster. This list include the
// local node ID and is the same order across all nodes. Only valid after "init"
// message has been received.
func (n *Node) NodeIDs() []string {
	return n.nodeIDs
}

// Handle registers a message handler for a given message type. Will panic if
// registering multiple handlers for the same message type.
func (n *Node) Handle(typ string, fn HandlerFunc) {
	if _, ok := n.handlers[typ]; ok {
		panic(fmt.Sprintf("duplicate message handler for %q message type", typ))
	}
	n.handlers[typ] = fn
}

// Run executes the main event handling loop. It reads in messages from STDIN
// and delegates them to the appropriate registered handler. This should be
// the last function executed by main().
func (n *Node) Run() error {
	scanner := bufio.NewScanner(n.Stdin)
	for scanner.Scan() {
		line := scanner.Bytes()

		// Parse next line from STDIN as a JSON-formatted message.
		var msg Message
		if err := json.Unmarshal(line, &msg); err != nil {
			return fmt.Errorf("unmarshal message: %w", err)
		}

		var body MessageBody
		if err := json.Unmarshal(msg.Body, &body); err != nil {
			return fmt.Errorf("unmarshal message body: %w", err)
		}
		log.Printf("Received %s", msg)

		// The init message has special handling. It is processed synchronously
		// to avoid race conditions and the node handles the reply itself.
		if body.Type == "init" {
			if err := n.handleInit(msg); err != nil {
				return fmt.Errorf("handle init: %w", err)
			}
			continue
		}

		// What handler should we use for this message?
		var h HandlerFunc
		if body.InReplyTo != 0 {
			// Extract callback, if replying to a previous message.
			n.mu.Lock()
			h = n.callbacks[body.InReplyTo]
			delete(n.callbacks, body.InReplyTo)
			n.mu.Unlock()

			// If no callback exists, just log a message and skip.
			if h == nil {
				log.Printf("Ignoring reply to %d with no callback", body.InReplyTo)
				continue
			}
		} else {
			// If this is not a callback, ensure that a handler is registered.
			h = n.handlers[body.Type]
			if h == nil {
				return fmt.Errorf("No handler for %s", line)
			}
		}

		// Handle message in a separate goroutine.
		n.wg.Add(1)
		go func() {
			defer n.wg.Done()
			n.handle(h, msg)
		}()
	}
	if err := scanner.Err(); err != nil {
		return err
	}

	// Wait for all in-flight handlers to complete.
	n.wg.Wait()

	return nil
}

// handle sends msg to a handler function. Sends an RPC error if an error is returned.
func (n *Node) handle(h HandlerFunc, msg Message) {
	if err := h(msg); err != nil {
		switch err := err.(type) {
		case *RPCError:
			if err := n.Reply(msg, err); err != nil {
				log.Printf("reply error: %s", err)
			}
		default:
			log.Printf("Exception handling %#v:\n%s", msg, err)
			if err := n.Reply(msg, NewRPCError(Crash, err.Error())); err != nil {
				log.Printf("reply error: %s", err)
			}
		}
	}
}

func (n *Node) handleInit(msg Message) error {
	var body InitMessageBody
	if err := json.Unmarshal(msg.Body, &body); err != nil {
		return fmt.Errorf("unmarshal init message body: %w", err)
	}
	n.id = body.NodeID
	n.nodeIDs = body.NodeIDs

	// Delegate to application initialization handler, if specified.
	if h := n.handlers["init"]; h != nil {
		if err := h(msg); err != nil {
			return err
		}
	}

	// Send back a response that the node has been initialized.
	log.Printf("Node %s initialized", n.id)
	return n.Reply(msg, MessageBody{Type: "init_ok"})
}

// Reply replies to a request with a response body.
func (n *Node) Reply(req Message, body any) error {
	// Extract the message ID from the original message.
	var reqBody MessageBody
	if err := json.Unmarshal(req.Body, &reqBody); err != nil {
		return err
	}

	// We have to marshal/unmarshal to inject our reply message ID.
	b := make(map[string]any)
	if buf, err := json.Marshal(body); err != nil {
		return err
	} else if err := json.Unmarshal(buf, &b); err != nil {
		return err
	}
	b["in_reply_to"] = reqBody.MsgID

	return n.Send(req.Src, b)
}

// Send sends a message body to a given destination node.
func (n *Node) Send(dest string, body any) error {
	bodyJSON, err := json.Marshal(body)
	if err != nil {
		return err
	}

	buf, err := json.Marshal(Message{
		Src:  n.id,
		Dest: dest,
		Body: bodyJSON,
	})
	if err != nil {
		return err
	}

	// Synchronize access to STDOUT.
	n.mu.Lock()
	defer n.mu.Unlock()

	log.Printf("Sent %s", buf)

	if _, err = n.Stdout.Write(buf); err != nil {
		return err
	}
	_, err = n.Stdout.Write([]byte{'\n'})
	return err
}

// Broadcast sends a message to all other nodes.
func (n *Node) Broadcast(body any) error {
	for _, id := range n.nodeIDs {
		if id == n.id {
			continue
		}
		if err := n.Send(id, body); err != nil {
			return err
		}
	}

	return nil
}

// RPC send an async RPC request. Handler invoked when response message received.
func (n *Node) RPC(dest string, body any, handler HandlerFunc) error {
	n.mu.Lock()

	// Generate a unique message ID.
	n.nextMsgID++
	msgID := n.nextMsgID

	// Register a handler for our callback.
	n.callbacks[msgID] = handler

	n.mu.Unlock()

	// We have to marshal/unmarshal to inject our message ID.
	b := make(map[string]any)
	if buf, err := json.Marshal(body); err != nil {
		return err
	} else if err := json.Unmarshal(buf, &b); err != nil {
		return err
	}
	b["msg_id"] = msgID

	return n.Send(dest, b)
}

// BroadcastRPC sends an RPC message to all other nodes.
func (n *Node) BroadcastRPC(body any, handler HandlerFunc) error {
	for _, id := range n.nodeIDs {
		if id == n.id {
			continue
		}
		if err := n.RPC(id, body, handler); err != nil {
			return err
		}
	}
	return nil
}

// Message represents a message sent from Src node to Dest node.
// The body is stored as unparsed JSON so the handler can parse it itself.
type Message struct {
	Src  string          `json:"src,omitempty"`
	Dest string          `json:"dest,omitempty"`
	Body json.RawMessage `json:"body,omitempty"`
}

// MessageBody represents the reserved keys for a message body.
type MessageBody struct {
	Type      string `json:"type,omitempty"`
	MsgID     int    `json:"msg_id,omitempty"`
	InReplyTo int    `json:"in_reply_to,omitempty"`
}

// InitMessageBody represents the message body for the "init" message.
type InitMessageBody struct {
	MessageBody
	NodeID  string   `json:"node_id,omitempty"`
	NodeIDs []string `json:"node_ids,omitempty"`
}

// HandlerFunc is the function signature for a message handler.
type HandlerFunc func(msg Message) error

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

// RPCError represents a Maelstrom RPC error.
type RPCError struct {
	code int
	text string
}

// NewRPCError returns a new instance of RPCError.
func NewRPCError(code int, text string) *RPCError {
	return &RPCError{
		code: code,
		text: text,
	}
}

// Error returns a string-formatted error message.
func (e *RPCError) Error() string {
	return fmt.Sprintf("RPCError(%s, %q)", ErrorCodeText(e.code), e.text)
}

// MarshalJSON marshals the error into JSON format.
func (e *RPCError) MarshalJSON() ([]byte, error) {
	return json.Marshal(rpcErrorJSON{
		Type: "error",
		Code: e.code,
		Text: e.text,
	})
}

// rpcErrorJSON is a struct for marshaling an RPCError to JSON.
type rpcErrorJSON struct {
	Type string `json:"type,omitempty"`
	Code int    `json:"code,omitempty"`
	Text string `json:"text,omitempty"`
}
