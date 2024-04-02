package structs

type ResponseBody interface {
	SetInReplyTo(float64)
}

type InitOkMsgBody struct {
	Type      MsgType `mapstructure:"type" json:"type"`
	InReplyTo float64 `mapstructure:"in_reply_to" json:"in_reply_to"`
}

func (msgBody InitOkMsgBody) SetInReplyTo(inReplyTo float64) {
	msgBody.InReplyTo = inReplyTo
}

type ReadOkMsgBody struct {
	Type      MsgType `mapstructure:"type" json:"type"`
	Value     float64 `mapstructure:"value" json:"value"`
	InReplyTo float64 `mapstructure:"in_reply_to" json:"in_reply_to"`
}

func (msgBody ReadOkMsgBody) SetInReplyTo(inReplyTo float64) {
	msgBody.InReplyTo = inReplyTo
}

type WriteOkMsgBody struct {
	Type      MsgType `mapstructure:"type" json:"type"`
	InReplyTo float64 `mapstructure:"in_reply_to" json:"in_reply_to"`
}

func (msgBody WriteOkMsgBody) SetInReplyTo(inReplyTo float64) {
	msgBody.InReplyTo = inReplyTo
}

type CasOkMsgBody struct {
	Type      MsgType `mapstructure:"type" json:"type"`
	InReplyTo float64 `mapstructure:"in_reply_to" json:"in_reply_to"`
}

func (msgBody CasOkMsgBody) SetInReplyTo(inReplyTo float64) {
	msgBody.InReplyTo = inReplyTo
}

type ErrorMsgBody struct {
	Type      MsgType `mapstructure:"type" json:"type"`
	Code      ErrCode `mapstructure:"code" json:"code"`
	Text      string  `mapstructure:"text" json:"text"`
	InReplyTo float64 `mapstructure:"in_reply_to" json:"in_reply_to"`
}

func (msgBody ErrorMsgBody) SetInReplyTo(inReplyTo float64) {
	msgBody.InReplyTo = inReplyTo
}

type RequestVoteResMsgBody struct {
	Type         MsgType `mapstructure:"type" json:"type"`
	Term         float64 `mapstructure:"term" json:"term"`
	VotedGranted bool    `mapstructure:"vote_granted" json:"vote_granted"`
	InReplyTo    float64 `mapstructure:"in_reply_to" json:"in_reply_to"`
}

func (msgBody RequestVoteResMsgBody) SetInReplyTo(inReplyTo float64) {
	msgBody.InReplyTo = inReplyTo
}

type AppendEntriesResMsgBody struct {
	Type      MsgType `mapstructure:"type" json:"type"`
	Term      float64 `mapstructure:"term" json:"term"`
	Success   bool    `mapstructure:"success" json:"success"`
	InReplyTo float64 `mapstructure:"in_reply_to" json:"in_reply_to"`
}

func (msgBody AppendEntriesResMsgBody) SetInReplyTo(inReplyTo float64) {
	msgBody.InReplyTo = inReplyTo
}
