package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"log"
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
	log.Printf("Sent\n %v", msg)
	jsonBytes, _ := json.MarshalIndent(msg, "", "  ")
	fmt.Println(string(jsonBytes))
	os.Stdout.Sync()
}

func (net *Net) send(dest string, body MsgBody) {
	// Sends a message to the given destination node with the given body.
	net.sendMsg(Msg{
		src:  net.nodeId,
		dest: dest,
		body: body,
	})
}

func (net *Net) reply(req Msg, body MsgBody) {
	body.inReplyTo = req.body.msgId
	net.send(req.src, body)
}

func (net *Net) rpc(dest string, body MsgBody, handler MsgHandler) {
	// Sends an RPC request to dest and handles the response with handler.
	msgId := net.newMsgId()
	net.callbacks[msgId] = handler
	body.msgId = &msgId
	net.send(dest, body)
}

func (net *Net) processMsg() (bool, error) {
	// Handles a message from stdin, if one is currently available.
	file, err := os.Stdin.Stat()
	if err != nil {
		return false, err
	}

	if file.Size() == 0 {
		return false, nil
	}

	scanner := bufio.NewScanner(os.Stdin)
	if scanner.Scan() {
		txt := scanner.Text()
		log.Println("Received\n", txt)

		var msg Msg
		if err := json.Unmarshal([]byte(txt), &msg); err != nil {
			return false, err
		}

		var handler MsgHandler
		if msg.body.inReplyTo != nil {
			handler = net.callbacks[*msg.body.inReplyTo]
			net.callbacks[*msg.body.inReplyTo] = nil
		} else if value, ok := net.handlers[string(msg.body.Type)]; ok {
			handler = value
		} else {
			return false, fmt.Errorf("No callback or handler for\n %v", msg)
		}

		handler(msg)
		return true, nil
	}

	// nothing to process
	return false, nil
}

func newNet() *Net {
	net := Net{}
	net.init()
	return &net
}
