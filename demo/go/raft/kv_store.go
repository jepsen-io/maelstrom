package main

import (
	"fmt"
	"github.com/pavan/maelstrom/demo/go/raft/structs"
	"log"
)

// KVStore A state machine providing a Key-Value store.
type KVStore struct {
	state map[float64]float64
}

func (kvStore *KVStore) init() {
	kvStore.state = map[float64]float64{}
}

func (kvStore *KVStore) apply(op structs.Operation) structs.OperationResponse {
	// Applies an op to the state machine, and returns a response message.
	t := op.Type
	k := op.Key

	var response structs.OperationResponse
	var body structs.ResponseBody
	// Handle state transition
	if t == structs.MsgTypeRead {
		if value, ok := kvStore.state[k]; ok {
			body = structs.ReadOkMsgBody{
				Type:  structs.MsgTypeReadOk,
				Value: value,
			}
		} else {
			body = structs.ErrorMsgBody{
				Type: structs.MsgTypeError,
				Code: structs.ErrCodeKeyDoesNotExist,
				Text: structs.ErrTxtNotFound,
			}
		}
	} else if t == structs.MsgTypeWrite {
		kvStore.state[k] = op.Value
		body = structs.WriteOkMsgBody{
			Type: structs.MsgTypeWriteOk,
		}
	} else if t == structs.MsgTypeCas {
		if value, ok := kvStore.state[k]; !ok {
			body = structs.ErrorMsgBody{
				Type: structs.MsgTypeError,
				Code: structs.ErrCodeKeyDoesNotExist,
				Text: structs.ErrTxtNotFound,
			}
		} else if value != op.From {
			body = structs.ErrorMsgBody{
				Type: structs.MsgTypeError,
				Code: structs.ErrCodePreconditionFailed,
				Text: fmt.Sprintf(structs.ErrExpectedButHad, op.From, value),
			}
		} else {
			kvStore.state[k] = op.To
			body = structs.CasOkMsgBody{
				Type: structs.MsgTypeCasOk,
			}
		}
	}

	log.Printf("KV:\n %v \n", kvStore.state)

	body.SetInReplyTo(op.MsgId)
	response.Dest = op.Client
	return structs.OperationResponse{
		Dest: op.Client,
		Body: body,
	}
}

func newKVStore() *KVStore {
	kvStore := KVStore{}
	kvStore.init()
	return &kvStore
}
