package main

import maelstrom "github.com/jepsen-io/maelstrom/demo/go"

type Entry struct {
	Term int
	Op   *Operation
	Msg  maelstrom.Message
}

type Operation struct {
	// all op
	Type   MsgType
	MsgId  int
	Key    int
	Client string

	// for write op
	Value int

	// for cas op
	From int
	To   int
}

type MsgType string

const (
	MsgTypeRead                MsgType = "read"
	MsgTypeReadOk              MsgType = "read_ok"
	MsgTypeWrite               MsgType = "write"
	MsgTypeWriteOk             MsgType = "write_ok"
	MsgTypeCas                 MsgType = "cas"
	MsgTypeCasOk               MsgType = "cas_ok"
	MsgTypeRequestVote         MsgType = "request_vote"
	MsgTypeRequestVoteResult   MsgType = "request_vote_res"
	MsgTypeAppendEntries       MsgType = "append_entries"
	MsgTypeAppendEntriesResult MsgType = "append_entries_res"
	MsgTypeError               MsgType = "error"
)

type ErrCode int

const (
	ErrCodeTemporarilyUnavailable ErrCode = 11
	ErrCodeKeyDoesNotExist        ErrCode = 20
	ErrCodePreconditionFailed     ErrCode = 22
)

const (
	ErrNotLeader      = "not a leader"
	ErrTxtNotFound    = "not found"
	ErrExpectedButHad = "expected %d but had %d"
)
