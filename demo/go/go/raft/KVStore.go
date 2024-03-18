package main

// WIP

// KVStore A state machine providing a key-value store.
type KVStore struct {
	state map[string]int
}

func (kvStore *KVStore) init() {
	kvStore.state = map[string]string{}
}

func (kvStore *KVStore) apply(op Message) Msg {
	// Applies an op to the state machine, and returns a response message.
	t := op.Type
	k := op.Key

	var msg Message
	// Handle state transition
	if t == "read" {
		if _, ok := kvStore.state[k]; ok {
			msg = Message{
				Type:  "read_ok",
				Value: kvStore.state[k],
			}
		} else {
			msg = Message{
				Type:  "read_ok",
				Value: kvStore.state[k],
			}
		}
	} else if t == "write" {
		kvStore.state[k] = op.Value
		msg = Message{
			Type: "write_ok",
		}
	} else if t == "cas" {
		if value, ok := kvStore.state[k]; !ok {
			msg = Message{
				Type: "error",
				Code: 20,
				Text: "not found",
			}
		} else if value != op.From {

		}
	}
}
