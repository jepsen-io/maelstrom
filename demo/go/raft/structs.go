package main

type Msg struct {
	Id   int     `json:"id"`
	Src  string  `json:"src"`
	Dest string  `json:"dest"`
	Body MsgBody `json:"body,omitempty"`
}

type MsgType string

const (
	initMsgBodyType            MsgType = "init"
	initOkMsgBodyType          MsgType = "init_ok"
	readMsgType                MsgType = "read"
	readOkMsgType              MsgType = "read_ok"
	writeMsgType               MsgType = "write"
	writeOkMsgType             MsgType = "write_ok"
	casMsgType                 MsgType = "cas"
	casOkMsgType               MsgType = "cas_ok"
	requestVoteMsgType         MsgType = "request_vote"
	requestVoteResultMsgType   MsgType = "request_vote_res"
	appendEntriesMsgType       MsgType = "append_entries"
	appendEntriesResultMsgType MsgType = "append_entries_res"
	errorMsgType               MsgType = "error"
)

type MsgBody struct {
	Type      MsgType  `json:"type"`
	MsgId     int      `json:"msg_id"`
	Key       int      `json:"key"`
	InReplyTo *int     `json:"in_reply_to"`
	Value     int      `json:"value"`
	NodeId    string   `json:"node_id"`
	NodeIds   []string `json:"node_ids"`
	Code      int      `json:"code"`
	Text      string   `json:"text,omitempty"`

	// replicate log
	Term         int     `json:"term"`
	LeaderId     string  `json:"leader_id,omitempty"`
	PrevLogIndex int     `json:"prev_log_index"`
	PrevLogTerm  int     `json:"prev_log_term"`
	Entries      []Entry `json:"entries,omitempty"`
	LeaderCommit int     `json:"leader_commit"`
	Success      bool    `json:"success"`
	VotedGranted bool    `json:"vote_granted"`
	Client       string  `json:"client,omitempty"`

	// Broadcast vote request
	CandidateId  string `json:"candidate_id,omitempty"`
	LastLogIndex int    `json:"last_log_index"`
	LastLogTerm  int    `json:"last_log_term"`

	// cas operation (state transition)
	From int `json:"from"`
	To   int `json:"to"`
}
