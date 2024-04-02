package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"github.com/pavan/maelstrom/demo/go/raft/structs"
	"io"
	"log"
	"os"
	"syscall"
)

type MsgHandler func(msg structs.Msg) error

type Net struct {
	nodeId    string                         // Our local node ID
	nextMsgId float64                        // The next message ID we're going To allocate
	handlers  map[structs.MsgType]MsgHandler // A map of message types to handler functions
	callbacks map[float64]MsgHandler         // A map of message IDs To response handlers

	// stdin
	stdin io.Reader
}

func (net *Net) init() {
	net.nodeId = ""                                 // Our local node ID
	net.nextMsgId = 0                               // The next message ID we're going to allocate
	net.handlers = map[structs.MsgType]MsgHandler{} // A map of message types to handler functions
	net.callbacks = map[float64]MsgHandler{}        // A map of message IDs to response handlers
	net.stdin = os.Stdin
}

func (net *Net) setNodeId(id string) {
	net.nodeId = id
}

func (net *Net) newMsgId() float64 {
	// Generate a fresh message ID
	id := net.nextMsgId
	net.nextMsgId += 1
	return id
}

func (net *Net) on(msgType structs.MsgType, handler MsgHandler) error {
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
	log.Printf("Sent\n%s", string(jsonBytes))
	fmt.Println(string(jsonBytes))
	//if err := os.Stdout.Sync(); err != nil {
	//}
}

func (net *Net) send(dest string, body any) {
	// Sends a message To the given destination node with the given Body.

	// deserialize back to unstructured
	bytes, err := json.Marshal(body)
	if err != nil {
		panic(err)
	}
	var bodyMap map[string]interface{}
	if err = json.Unmarshal(bytes, &bodyMap); err != nil {
		panic(err)
	}

	net.sendMsg(structs.Msg{
		Src:  net.nodeId,
		Dest: dest,
		Body: bodyMap,
	})
}

func (net *Net) reply(req structs.Msg, body structs.ResponseBody) {
	// Replies to a given request message with a response body
	body.SetInReplyTo(req.Body["msg_id"].(float64))
	net.send(req.Src, body)
}

func (net *Net) rpc(dest string, body structs.RequestBody, handler MsgHandler) {
	// Sends an RPC request to dest and handles the response with handler.
	msgId := net.newMsgId()
	log.Printf("new message for callback is %f: %v : %v \n", msgId, body, handler)
	net.callbacks[msgId] = handler
	body.SetMsgId(msgId)
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

	// if stdin has something to ready
	if FD_ISSET(rfds, stdinFD) {
		reader := bufio.NewReader(net.stdin)
		line, err := reader.ReadString('\n')
		if err != nil {
			return false, err
		}

		if line == "" {
			return false, nil
		}

		log.Println("Received\n", line)

		var msg structs.Msg
		if err = json.Unmarshal([]byte(line), &msg); err != nil {
			return false, err
		}

		var handler MsgHandler
		if msg.Body["in_reply_to"] != nil {
			handler = net.callbacks[msg.Body["in_reply_to"].(float64)]
			net.callbacks[msg.Body["in_reply_to"].(float64)] = nil
			log.Printf("removing callback for msgId: %f \n", msg.Body["in_reply_to"].(float64))
		} else if value, ok := net.handlers[structs.MsgType(msg.Body["type"].(string))]; ok {
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
