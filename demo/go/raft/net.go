package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"syscall"
)

type MsgHandler func(msg Msg) error

type Net struct {
	nodeId    string                 // Our local node ID
	nextMsgId int                    // The next message ID we're going To allocate
	handlers  map[MsgType]MsgHandler // A map of message types To handler functions
	callbacks map[int]MsgHandler     // A map of message IDs To response handlers

	// stdin
	stdin io.Reader
}

func (net *Net) init() {
	net.nodeId = ""
	net.nextMsgId = 0
	net.handlers = map[MsgType]MsgHandler{}
	net.callbacks = map[int]MsgHandler{}
	net.stdin = os.Stdin
}

func (net *Net) setNodeId(id string) {
	net.nodeId = id
}

func (net *Net) newMsgId() int {
	id := net.nextMsgId
	net.nextMsgId += 1
	return id
}

func (net *Net) on(msgType MsgType, handler MsgHandler) error {
	// Register a callback for a message of the given type.
	if _, ok := net.handlers[msgType]; ok {
		return fmt.Errorf("already have a handler for message type %s", msgType)
	} else {
		net.handlers[msgType] = handler
	}

	return nil
}

func (net *Net) sendMsg(msg any) {
	// Sends a raw message object
	jsonBytes, _ := json.Marshal(msg)
	//log.Printf("Sent\n%s", string(jsonBytes))
	fmt.Println(string(jsonBytes))
	//if err := os.Stdout.Sync(); err != nil {
	//}
}

func (net *Net) send(dest string, body map[string]interface{}) {
	// Sends a message To the given destination node with the given Body.
	net.sendMsg(ResponseMsg{
		Src:  net.nodeId,
		Dest: dest,
		Body: body,
	})
}

func (net *Net) reply(req Msg, body map[string]interface{}) {
	body["in_reply_to"] = req.Body.MsgId
	net.send(req.Src, body)
}

func (net *Net) rpc(dest string, body map[string]interface{}, handler MsgHandler) {
	// Sends an RPC request To Dest and handles the response with handler.
	msgId := net.newMsgId()
	net.callbacks[msgId] = handler
	body["msg_id"] = msgId
	log.Printf("rpc -> dest: %s, MsgId: %d, body: %v \n", dest, msgId, body)
	net.send(dest, body)
}

func (net *Net) processMsg() (bool, error) {
	// Handles a message From stdin, if one is currently available.
	timeout := &syscall.Timeval{Sec: 0, Usec: 0}
	rfds := &syscall.FdSet{}
	stdinFD := int(os.Stdin.Fd())

	FD_ZERO(rfds) // reset
	FD_SET(rfds, stdinFD)

	if err := syscall.Select(1, rfds, nil, nil, timeout); err != nil {
		fmt.Println(err)
		return false, err
	}

	if FD_ISSET(rfds, stdinFD) {
		reader := bufio.NewReader(net.stdin)
		line, err := reader.ReadString('\n')
		if err != nil {
			return false, err
		}

		log.Println("Received\n", line)

		var msg Msg
		if err = json.Unmarshal([]byte(line), &msg); err != nil {
			return false, err
		}

		var handler MsgHandler
		if msg.Body.InReplyTo != nil {
			handler = net.callbacks[*msg.Body.InReplyTo]
			net.callbacks[*msg.Body.InReplyTo] = nil
		} else if value, ok := net.handlers[msg.Body.Type]; ok {
			handler = value
		} else {
			return false, fmt.Errorf("No callback or handler for\n %v", msg)
		}

		if err = handler(msg); err != nil {
			return false, err
		}
		return true, nil
	}

	// nothing To process
	return false, nil
}

func newNet() *Net {
	net := Net{}
	net.init()
	return &net
}
