package main

import (
	"fmt"
	"log"
	"sync"
)

// KVStore A state machine providing a Key-Value store.
type KVStore struct {
	state map[int]int
	wu    sync.Mutex
}

func (kvStore *KVStore) init() {
	kvStore.state = map[int]int{}
}

func (kvStore *KVStore) apply(op Operation) any {
	kvStore.wu.Lock()
	defer kvStore.wu.Unlock()
	// Applies an operation to the state machine, and returns a response message.
	t := op.Type
	k := op.Key

	var body any
	// Handle state transition
	if t == MsgTypeRead {
		if value, ok := kvStore.state[k]; ok {
			body = &ReadOkMsgBody{
				Type:  MsgTypeReadOk,
				Value: value,
			}
		} else {
			body = &ErrorMsgBody{
				Type: MsgTypeError,
				Code: ErrCodeKeyDoesNotExist,
				Text: ErrTxtNotFound,
			}
		}
	} else if t == MsgTypeWrite {
		kvStore.state[k] = op.Value
		body = &WriteOkMsgBody{
			Type: MsgTypeWriteOk,
		}
	} else if t == MsgTypeCas {
		if value, ok := kvStore.state[k]; !ok {
			body = &ErrorMsgBody{
				Type: MsgTypeError,
				Code: ErrCodeKeyDoesNotExist,
				Text: ErrTxtNotFound,
			}
		} else if value != op.From {
			body = &ErrorMsgBody{
				Type: MsgTypeError,
				Code: ErrCodePreconditionFailed,
				Text: fmt.Sprintf(ErrExpectedButHad, op.From, value),
			}
		} else {
			kvStore.state[k] = op.To
			body = &CasOkMsgBody{
				Type: MsgTypeCasOk,
			}
		}
	}

	log.Printf("KV:\n %v \n", kvStore.state)
	return body
}

func newKVStore() *KVStore {
	kvStore := KVStore{}
	kvStore.init()
	return &kvStore
}
