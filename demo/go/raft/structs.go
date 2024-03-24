package main

type Message struct {
	Type      string   `json:"type,omitempty"`
	MsgId     int      `json:"msg_id,omitempty"`
	InReplyTo int      `json:"in_reply_to,omitempty"`
	Key       string   `json:"key,omitempty"`
	Value     int      `json:"value,omitempty"`
	NodeId    string   `json:"node_id,omitempty"`
	NodeIds   []string `json:"node_ids,omitempty"`
	Code      int      `json:"code,omitempty"`
	Text      string   `json:"text,omitempty"`
	From      int      `json:"from,omitempty"`
	To        int      `json:"to,omitempty"`
}

type Body struct {
	Type         string
	term         int
	leaderId     string
	prevLogIndex int
	prevLogTerm  int
	entries      []Entry
	leaderCommit int
	msgId        int
	inReplyTo    int
	success      bool
	votedGranted bool

	candidateId  string
	lastLogIndex int
	lastLogTerm  int
}

type Msg struct {
	src  string
	dest string
	body MsgBody
}

type KVMsg struct {
	dest string
	body Message
}

type MsgBodyType string

const (
	readMsgBodyType   MsgBodyType = "read"
	readOkMsgBodyType MsgBodyType = "read_ok"
	initMsgBodyType   MsgBodyType = "init"
	initOkMsgBodyType MsgBodyType = "init_ok"
	errorMsgBodyType  MsgBodyType = "error"
)

const (
	requestVoteMsgBodyType MsgBodyType = "request_vote"
)

type MsgBody struct {
	Type      MsgBodyType
	msgId     *int
	key       int
	inReplyTo *int
	value     string
	nodeId    string
	nodeIds   []string
	code      int
	text      string

	term         int
	leaderId     string
	prevLogIndex int
	prevLogTerm  int
	entries      []Entry
	leaderCommit int
	success      bool
	votedGranted bool

	candidateId  string
	lastLogIndex int
	lastLogTerm  int
}
