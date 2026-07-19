package main

type ReadOkMsgBody struct {
	Type      MsgType `mapstructure:"type" json:"type"`
	Value     int     `mapstructure:"value" json:"value"`
	InReplyTo int     `mapstructure:"in_reply_to" json:"in_reply_to"`
}

type WriteOkMsgBody struct {
	Type      MsgType `mapstructure:"type" json:"type"`
	InReplyTo int     `mapstructure:"in_reply_to" json:"in_reply_to"`
}

type CasOkMsgBody struct {
	Type      MsgType `mapstructure:"type" json:"type"`
	InReplyTo int     `mapstructure:"in_reply_to" json:"in_reply_to"`
}

type ErrorMsgBody struct {
	Type      MsgType `mapstructure:"type" json:"type"`
	Code      ErrCode `mapstructure:"code" json:"code"`
	Text      string  `mapstructure:"text" json:"text"`
	InReplyTo int     `mapstructure:"in_reply_to" json:"in_reply_to"`
}

type RequestVoteResMsgBody struct {
	Type         MsgType `mapstructure:"type" json:"type"`
	Term         int     `mapstructure:"term" json:"term"`
	VotedGranted bool    `mapstructure:"vote_granted" json:"vote_granted"`
	InReplyTo    int     `mapstructure:"in_reply_to" json:"in_reply_to"`
}

type AppendEntriesResMsgBody struct {
	Type      MsgType `mapstructure:"type" json:"type"`
	Term      int     `mapstructure:"term" json:"term"`
	Success   bool    `mapstructure:"success" json:"success"`
	InReplyTo int     `mapstructure:"in_reply_to" json:"in_reply_to"`
}
