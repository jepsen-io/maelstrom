package main

type OpType string

const (
	readOpType  OpType = "read"
	writeOpType OpType = "write"
	casOpType   OpType = "cas"
)

type Op struct {
	Type   OpType
	MsgId  int
	Key    string
	Value  string
	From   string
	To     string
	Client string
}
