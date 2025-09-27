package main

type ReadMsgBody struct {
	Type   MsgType `mapstructure:"type" json:"type"`
	MsgId  int     `mapstructure:"msg_id" json:"msg_id"`
	Key    int     `mapstructure:"key" json:"key"`
	Client string  `mapstructure:"client" json:"client"`
}

type WriteMsgBody struct {
	Type   MsgType `mapstructure:"type" json:"type"`
	MsgId  int     `mapstructure:"msg_id" json:"msg_id"`
	Key    int     `mapstructure:"key" json:"key"`
	Value  int     `mapstructure:"value" json:"value"`
	Client string  `mapstructure:"client" json:"client"`
}

type CasMsgBody struct {
	Type   MsgType `mapstructure:"type" json:"type"`
	MsgId  int     `mapstructure:"msg_id" json:"msg_id"`
	Key    int     `mapstructure:"key" json:"key"`
	From   int     `mapstructure:"from" json:"from"`
	To     int     `mapstructure:"to" json:"to"`
	Client string  `mapstructure:"client" json:"client"`
}

type AppendEntriesMsgBody struct {
	Type         MsgType `mapstructure:"type" json:"type"`
	MsgId        int     `mapstructure:"msg_id" json:"msg_id"`
	Term         int     `mapstructure:"term" json:"term"`
	LeaderId     string  `mapstructure:"leader_id" json:"leader_id"`
	PrevLogIndex int     `mapstructure:"prev_log_index" json:"prev_log_index"`
	PrevLogTerm  int     `mapstructure:"prev_log_term" json:"prev_log_term"`
	Entries      []Entry `mapstructure:"entries" json:"entries"`
	LeaderCommit int     `mapstructure:"leader_commit" json:"leader_commit"`
}

func (res *AppendEntriesMsgBody) SetMsgId(msgId int) {
	res.MsgId = msgId
}

type RequestVoteMsgBody struct {
	Type         MsgType `mapstructure:"type" json:"type"`
	MsgId        int     `mapstructure:"msg_id" json:"msg_id"`
	Term         int     `mapstructure:"term" json:"term"`
	CandidateId  string  `mapstructure:"candidate_id" json:"candidate_id"`
	LastLogIndex int     `mapstructure:"last_log_index" json:"last_log_index"`
	LastLogTerm  int     `mapstructure:"last_log_term" json:"last_log_term"`
}

func (res *RequestVoteMsgBody) SetMsgId(msgId int) {
	res.MsgId = msgId
}
