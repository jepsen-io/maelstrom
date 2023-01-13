package maelstrom

import (
	"context"
	"encoding/json"
)

// Types of key/value stores.
const (
	LinKV = "lin-kv"
	SeqKV = "seq-kv"
	LWWKV = "lww-kv"
)

// KV represents a client to the key/value store service.
type KV struct {
	typ  string
	node *Node
}

// NewKV returns a new instance a KV client for a node.
func NewKV(typ string, node *Node) *KV {
	return &KV{
		typ:  typ,
		node: node,
	}
}

// NewLinKV returns a client to the linearizable key/value store.
func NewLinKV(node *Node) *KV { return NewKV(LinKV, node) }

// NewSeqKV returns a client to the sequential key/value store.
func NewSeqKV(node *Node) *KV { return NewKV(SeqKV, node) }

// NewLWWKV returns a client to the last-write-wins key/value store.
func NewLWWKV(node *Node) *KV { return NewKV(LWWKV, node) }

// Read returns the value for a given key in the key/value store.
// Returns an *RPCError error with a KeyDoesNotExist code if the key does not exist.
func (kv *KV) Read(ctx context.Context, key string) (any, error) {
	resp, err := kv.node.SyncRPC(ctx, kv.typ, kvReadMessageBody{
		MessageBody: MessageBody{Type: "read"},
		Key:         key,
	})
	if err != nil {
		return nil, err
	}

	// Parse read_ok specific data in response message.
	var body kvReadOKMessageBody
	if err := json.Unmarshal(resp.Body, &body); err != nil {
		return nil, err
	}

	// Convert numbers to integers since that's what maelstrom workloads use.
	switch v := body.Value.(type) {
	case float64:
		return int(v), nil
	default:
		return v, nil
	}
}

// ReadInt reads the value of a key in the key/value store as an int.
func (kv *KV) ReadInt(ctx context.Context, key string) (int, error) {
	v, err := kv.Read(ctx, key)
	i, _ := v.(int)
	return i, err
}

// Write overwrites the value for a given key in the key/value store.
func (kv *KV) Write(ctx context.Context, key string, value any) error {
	_, err := kv.node.SyncRPC(ctx, kv.typ, kvWriteMessageBody{
		MessageBody: MessageBody{Type: "write"},
		Key:         key,
		Value:       value,
	})
	return err
}

// CompareAndSwap updates the value for a key if its current value matches the
// previous value. Creates the key if createIfNotExists is true.
//
// Returns an *RPCError with a code of PreconditionFailed if the previous value
// does not match. Return a code of KeyDoesNotExist if the key did not exist.
func (kv *KV) CompareAndSwap(ctx context.Context, key string, from, to any, createIfNotExists bool) error {
	_, err := kv.node.SyncRPC(ctx, kv.typ, kvCASMessageBody{
		MessageBody:       MessageBody{Type: "cas"},
		Key:               key,
		From:              from,
		To:                to,
		CreateIfNotExists: createIfNotExists,
	})
	return err
}

// kvReadMessageBody represents the body for the KV "read" message.
type kvReadMessageBody struct {
	MessageBody
	Key string `json:"key"`
}

// kvReadOKMessageBody represents the response body for the KV "read_ok" message.
type kvReadOKMessageBody struct {
	MessageBody
	Value any `json:"value"`
}

// kvWriteMessageBody represents the body for the KV "cas" message.
type kvWriteMessageBody struct {
	MessageBody
	Key   string `json:"key"`
	Value any    `json:"value"`
}

// kvCASMessageBody represents the body for the KV "cas" message.
type kvCASMessageBody struct {
	MessageBody
	Key               string `json:"key"`
	From              any    `json:"from"`
	To                any    `json:"to"`
	CreateIfNotExists bool   `json:"create_if_not_exists,omitempty"`
}
