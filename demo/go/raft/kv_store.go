package main

import (
	"fmt"
)

// WIP

// KVStore A state machine providing a Key-Value store.
type KVStore struct {
	state map[int]int
}

func (kvStore *KVStore) init() {
	kvStore.state = map[int]int{}
}

func (kvStore *KVStore) apply(op MsgBody) Msg {
	// Applies an op To the state machine, and returns a response message.
	t := op.Type
	k := op.Key

	var msgBody MsgBody
	// Handle state transition
	if t == readMsgType {
		if _, ok := kvStore.state[k]; ok {
			msgBody = MsgBody{
				Type:  readOkMsgType,
				Value: kvStore.state[k],
			}
		} else {
			msgBody = MsgBody{
				Type:  readOkMsgType,
				Value: kvStore.state[k],
			}
		}
	} else if t == writeMsgType {
		kvStore.state[k] = op.Value
		msgBody = MsgBody{
			Type: writeOkMsgType,
		}
	} else if t == casMsgType {
		if value, ok := kvStore.state[k]; !ok {
			msgBody = MsgBody{
				Type: errorMsgType,
				Code: 20,
				Text: "not found",
			}
		} else if value != op.From {
			msgBody = MsgBody{
				Type: errorMsgType,
				Code: 22,
				Text: fmt.Sprintf("expected %d but had %d", op.From, value),
			}
		} else {
			kvStore.state[k] = op.To
			msgBody = MsgBody{
				Type: casOkMsgType,
			}
		}
	}

	msgBody.InReplyTo = op.MsgId
	return Msg{
		Dest: op.Client,
		Body: msgBody,
	}
}

func newKVStore() *KVStore {
	kvStore := KVStore{}
	kvStore.init()
	return &kvStore
}
