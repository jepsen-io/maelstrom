package main

type Message struct {
	Type      string   `json:"type,omitempty"`
	MsgId     int      `json:"msg_id,omitempty"`
	InReplyTo string   `json:"in_reply_to,omitempty"`
	Key       string   `json:"key,omitempty"`
	Value     string   `json:"value,omitempty"`
	NodeId    string   `json:"node_id,omitempty"`
	NodeIds   []string `json:"node_ids,omitempty"`
	Code      int      `json:"code,omitempty"`
	Text      string   `json:"text,omitempty"`
	From      string   `json:"from,omitempty"`
	To        string   `json:"to,omitempty"`
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
	body Body
}

type KVMsg struct {
	dest string
	body Message
}

type Op struct {
	Type  string
	Key   string
	Value int
}
