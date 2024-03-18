package main

import (
	"encoding/json"
	"fmt"
	"os"
)

type MsgHandler func(msg Msg)

type Net struct {
	nodeId    string                // Our local node ID
	nextMsgId int                   // The next message ID we're going to allocate
	handlers  map[string]MsgHandler // A map of message types to handler functions
	callbacks map[int]MsgHandler    // A map of message IDs to response handlers
}

func (net *Net) init() {
	net.nodeId = ""
	net.nextMsgId = 0
	net.handlers = map[string]MsgHandler{}
	net.callbacks = map[int]MsgHandler{}
}

func (net *Net) setNodeId(id string) {
	net.nodeId = id
}

func (net *Net) newMsgId() int {
	id := net.nextMsgId
	net.nextMsgId += 1
	return id
}

func (net *Net) on(msgType string, handler MsgHandler) error {
	// Register a callback for a message of the given type.
	if handler, ok := net.handlers[msgType]; ok {
		return fmt.Errorf("already have a handler for message type %s", msgType)
	} else {
		net.handlers[msgType] = handler
	}

	return nil
}

func (net *Net) sendMsg(msg Msg) {
	// Sends a raw message object
	// fmt.Println("Sent\n" + pformat(msg))
	jsonBytes, _ := json.MarshalIndent(msg, "", "  ")
	fmt.Println(string(jsonBytes))
	os.Stdout.Sync()
}

func (net *Net) send(dest string, body Body) {
	// Sends a message to the given destination node with the given body.
	net.sendMsg(Msg{
		src:  net.nodeId,
		dest: dest,
		body: body,
	})
}

func (net *Net) reply(req Msg, body Body) {
	body.inReplyTo = req.body.msgId
	net.send(req.src, body)
}

func (net *Net) rpc(dest string, body Body, handler MsgHandler) {
	// Sends an RPC request to dest and handles the response with handler.
	msgId := net.newMsgId()
	net.callbacks[msgId] = handler
	body.msgId = msgId
	net.send(dest, body)
}
