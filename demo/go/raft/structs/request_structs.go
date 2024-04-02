package structs

type RequestBody interface {
	SetMsgId(float64)
}

type InitMsgBody struct {
	Type    MsgType  `mapstructure:"type" json:"type"`
	MsgId   float64  `mapstructure:"msg_id" json:"msg_id"`
	NodeId  string   `mapstructure:"node_id" json:"node_id"`
	NodeIds []string `mapstructure:"node_ids" json:"node_ids"`
}

type ReadMsgBody struct {
	Type   MsgType `mapstructure:"type" json:"type"`
	MsgId  float64 `mapstructure:"msg_id" json:"msg_id"`
	Key    float64 `mapstructure:"key" json:"key"`
	Client string  `mapstructure:"client" json:"client"`
}

type WriteMsgBody struct {
	Type   MsgType `mapstructure:"type" json:"type"`
	MsgId  float64 `mapstructure:"msg_id" json:"msg_id"`
	Key    float64 `mapstructure:"key" json:"key"`
	Value  float64 `mapstructure:"value" json:"value"`
	Client string  `mapstructure:"client" json:"client"`
}

type CasMsgBody struct {
	Type   MsgType `mapstructure:"type" json:"type"`
	MsgId  float64 `mapstructure:"msg_id" json:"msg_id"`
	Key    float64 `mapstructure:"key" json:"key"`
	From   float64 `mapstructure:"from" json:"from"`
	To     float64 `mapstructure:"to" json:"to"`
	Client string  `mapstructure:"client" json:"client"`
}

type AppendEntriesMsgBody struct {
	Type         MsgType `mapstructure:"type" json:"type"`
	MsgId        float64 `mapstructure:"msg_id" json:"msg_id"`
	Term         float64 `mapstructure:"term" json:"term"`
	LeaderId     string  `mapstructure:"leader_id" json:"leader_id"`
	PrevLogIndex int     `mapstructure:"prev_log_index" json:"prev_log_index"`
	PrevLogTerm  float64 `mapstructure:"prev_log_term" json:"prev_log_term"`
	Entries      []Entry `mapstructure:"entries" json:"entries"`
	LeaderCommit int     `mapstructure:"leader_commit" json:"leader_commit"`
}

func (res AppendEntriesMsgBody) SetMsgId(msgId float64) {
	res.MsgId = msgId
}

type RequestVoteMsgBody struct {
	Type         MsgType `mapstructure:"type" json:"type"`
	MsgId        float64 `mapstructure:"msg_id" json:"msg_id"`
	Term         float64 `mapstructure:"term" json:"term"`
	CandidateId  string  `mapstructure:"candidate_id" json:"candidate_id"`
	LastLogIndex int     `mapstructure:"last_log_index" json:"last_log_index"`
	LastLogTerm  float64 `mapstructure:"last_log_term" json:"last_log_term"`
}

func (res RequestVoteMsgBody) SetMsgId(msgId float64) {
	res.MsgId = msgId
}
