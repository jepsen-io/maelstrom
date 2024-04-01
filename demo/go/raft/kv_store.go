package main

import (
	"fmt"
)

// KVStore A state machine providing a Key-Value store.
type KVStore struct {
	state map[int]int
}

func (kvStore *KVStore) init() {
	kvStore.state = map[int]int{}
}

func (kvStore *KVStore) apply(op MsgBody) ResponseMsg {
	// Applies an Op To the state machine, and returns a response message.
	t := op.Type
	k := op.Key

	var response map[string]interface{}
	// Handle state transition
	if t == readMsgType {
		if _, ok := kvStore.state[k]; ok {
			response = map[string]interface{}{
				"type":  readOkMsgType,
				"value": kvStore.state[k],
			}
		} else {
			response = map[string]interface{}{
				"type": errorMsgType,
				"code": 20,
				"text": "not found",
			}
		}
	} else if t == writeMsgType {
		kvStore.state[k] = op.Value
		response = map[string]interface{}{
			"type": writeOkMsgType,
		}
	} else if t == casMsgType {
		if value, ok := kvStore.state[k]; !ok {
			response = map[string]interface{}{
				"type": errorMsgType,
				"code": 20,
				"text": "not found",
			}
		} else if value != op.From {
			response = map[string]interface{}{
				"type": errorMsgType,
				"code": 22,
				"text": fmt.Sprintf("expected %d but had %d", op.From, value),
			}
		} else {
			kvStore.state[k] = op.To
			response = map[string]interface{}{
				"type": casOkMsgType,
			}
		}
	}

	response["in_reply_to"] = op.MsgId
	return ResponseMsg{
		Dest: op.Client,
		Body: response,
	}
}

func newKVStore() *KVStore {
	kvStore := KVStore{}
	kvStore.init()
	return &kvStore
}
