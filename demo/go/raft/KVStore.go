package main

import "fmt"

// WIP

// KVStore A state machine providing a key-value store.
type KVStore struct {
	state map[string]string
}

func (kvStore *KVStore) init() {
	kvStore.state = map[string]string{}
}

func (kvStore *KVStore) apply(op Op) Msg {
	// Applies an op to the state machine, and returns a response message.
	t := op.Type
	k := op.Key

	var msgBody MsgBody
	// Handle state transition
	if t == readOpType {
		if _, ok := kvStore.state[k]; ok {
			msgBody = MsgBody{
				Type:  "read_ok",
				value: kvStore.state[k],
			}
		} else {
			msgBody = MsgBody{
				Type:  "read_ok",
				value: kvStore.state[k],
			}
		}
	} else if t == writeOpType {
		kvStore.state[k] = op.Value
		msgBody = MsgBody{
			Type: "write_ok",
		}
	} else if t == casOpType {
		if value, ok := kvStore.state[k]; !ok {
			msgBody = MsgBody{
				Type: "error",
				code: 20,
				text: "not found",
			}
		} else if value != op.From {
			msgBody = MsgBody{
				Type: "error",
				code: 22,
				text: fmt.Sprintf("expected %d but had %d", op.From, value),
			}
		} else {
			kvStore.state[k] = op.To
			msgBody = MsgBody{
				Type: "cas_ok",
			}
		}
	}

	msgBody.inReplyTo = op.MsgId
	return Msg{
		dest: op.Client,
		body: msgBody,
	}
}
