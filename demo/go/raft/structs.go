package main

type Msg struct {
	Id   int     `json:"id,omitempty"`
	Src  string  `json:"src,omitempty"`
	Dest string  `json:"dest,omitempty"`
	Body MsgBody `json:"body,omitempty"`
}

type MsgType string

const (
	initMsgBodyType   MsgType = "init"
	initOkMsgBodyType MsgType = "init_ok"
	readMsgType       MsgType = "read"
	readOkMsgType     MsgType = "read_ok"
	writeMsgType      MsgType = "write"
	writeOkMsgType    MsgType = "write_ok"
	casMsgType        MsgType = "cas"
	casOkMsgType      MsgType = "cas_ok"
)

const (
	requestVoteMsgType         MsgType = "request_vote"
	requestVoteResultMsgType   MsgType = "request_vote_res"
	appendEntriesMsgType       MsgType = "append_entries"
	appendEntriesResultMsgType MsgType = "append_entries_res"
	errorMsgType               MsgType = "error"
)

type MsgBody struct {
	Type      MsgType  `json:"type"`
	MsgId     *int     `json:"msg_id,omitempty"`
	Key       int      `json:"key,omitempty"`
	InReplyTo *int     `json:"in_reply_to,omitempty"`
	Value     int      `json:"value,omitempty"`
	NodeId    string   `json:"node_id,omitempty"`
	NodeIds   []string `json:"node_ids,omitempty"`
	Code      int      `json:"code,omitempty"`
	Text      string   `json:"text,omitempty"`

	// replicate log
	Term         int     `json:"term,omitempty"`
	LeaderId     string  `json:"leader_id,omitempty"`
	PrevLogIndex int     `json:"prev_log_index,omitempty"`
	PrevLogTerm  int     `json:"prev_log_term,omitempty"`
	Entries      []Entry `json:"entries,omitempty"`
	LeaderCommit int     `json:"leader_commit	,omitempty"`
	Success      bool    `json:"success,omitempty"`
	VotedGranted bool    `json:"vote_granted,omitempty"`
	Client       string  `json:"client,omitempty"`

	// Broadcast vote request
	CandidateId  string `json:"candidate_id,omitempty"`
	LastLogIndex int    `json:"last_log_index,omitempty"`
	LastLogTerm  int    `json:"last_log_term,omitempty"`

	// state transition
	From int `json:"from,omitempty"`
	To   int `json:"to,omitempty"`
}
