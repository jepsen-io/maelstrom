package structs

type Msg struct {
	Id   float64                `json:"id"`
	Src  string                 `json:"src"`
	Dest string                 `json:"dest"`
	Body map[string]interface{} `json:"body"`
}

type Entry struct {
	Term float64
	Op   Operation
}

type Operation struct {
	// all op
	Type   MsgType
	MsgId  float64
	Key    float64
	Client string

	// for write op
	Value float64

	// for cas op
	From float64
	To   float64
}

type OperationResponse struct {
	Dest string
	Body ResponseBody
}

type MsgType string

const (
	MsgTypeInit                MsgType = "init"
	MsgTypeInitOk              MsgType = "init_ok"
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
	ErrCodeTimeOut                ErrCode = 0
	ErrCodeNodeNotFound           ErrCode = 1
	ErrCodeTemporarilyUnavailable ErrCode = 11
	ErrCodeKeyDoesNotExist        ErrCode = 20
	ErrCodeKeyAlreadyExists       ErrCode = 21
	ErrCodePreconditionFailed     ErrCode = 22
)

const (
	ErrNotLeader      = "not a leader"
	ErrTxtNotFound    = "not found"
	ErrExpectedButHad = "expected %f but had %f"
)
