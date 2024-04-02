package main

import (
	"fmt"
	mapstructure "github.com/mitchellh/mapstructure"
	"github.com/pavan/maelstrom/demo/go/raft/structs"
	"log"
)

func (raft *RaftNode) setupHandlers() error {
	// Handle initialization message
	raftInit := func(msg structs.Msg) error {
		if raft.state != StateNascent {
			return fmt.Errorf("Can't init twice")
		}

		var initMsgBody structs.InitMsgBody
		err := mapstructure.Decode(msg.Body, &initMsgBody)
		if err != nil {
			panic(err)
		}

		raft.setNodeId(initMsgBody.NodeId)
		raft.nodeIds = initMsgBody.NodeIds
		raft.becomeFollower()

		log.Println("I am: ", raft.nodeId)

		raft.net.reply(msg, structs.InitOkMsgBody{
			Type: structs.MsgTypeInitOk,
		})
		return nil
	}

	if err := raft.net.on(structs.MsgTypeInit, raftInit); err != nil {
		return err
	}

	// When a node requests our vote...
	requestVote := func(msg structs.Msg) error {
		var requestVoteMsgBody structs.RequestVoteMsgBody
		err := mapstructure.Decode(msg.Body, &requestVoteMsgBody)
		if err != nil {
			panic(err)
		}

		if err := raft.maybeStepDown(requestVoteMsgBody.Term); err != nil {
			return err
		}
		grant := false

		if requestVoteMsgBody.Term < raft.currentTerm {
			log.Printf("candidate term %f lower than %f not granting vote \n", requestVoteMsgBody.Term, raft.currentTerm)
		} else if raft.votedFor != "" {
			log.Printf("already voted for %s not granting vote \n", raft.votedFor)
		} else if requestVoteMsgBody.LastLogTerm < raft.log.lastTerm() {
			log.Printf("have log entries From Term %f which is newer than remote term %f not granting vote\n", raft.log.lastTerm(), requestVoteMsgBody.LastLogTerm)
		} else if requestVoteMsgBody.LastLogTerm == raft.log.lastTerm() && requestVoteMsgBody.LastLogIndex < raft.log.size() {
			log.Printf("our logs are both at term %f but our log is %d and theirs is only %d \n", raft.log.lastTerm(), raft.log.size(), requestVoteMsgBody.LastLogIndex)
		} else {
			log.Printf("Granting vote to %s\n", msg.Src)
			grant = true
			raft.votedFor = requestVoteMsgBody.CandidateId
			raft.resetElectionDeadline()
		}

		raft.net.reply(msg, structs.RequestVoteResMsgBody{
			Type:         structs.MsgTypeRequestVoteResult,
			Term:         raft.currentTerm,
			VotedGranted: grant,
		})

		return nil
	}
	if err := raft.net.on(structs.MsgTypeRequestVote, requestVote); err != nil {
		return err
	}

	// When we're given entries by a leader
	appendEntries := func(msg structs.Msg) error {
		var appendEntriesMsgBody structs.AppendEntriesMsgBody
		err := mapstructure.Decode(msg.Body, &appendEntriesMsgBody)
		if err != nil {
			panic(err)
		}

		if err := raft.maybeStepDown(appendEntriesMsgBody.Term); err != nil {
			return err
		}

		result := structs.AppendEntriesResMsgBody{
			Type:    structs.MsgTypeAppendEntriesResult,
			Term:    raft.currentTerm,
			Success: false,
		}

		if appendEntriesMsgBody.Term < raft.currentTerm {
			// leader is behind us
			raft.net.reply(msg, result)
			return nil
		}

		// This leader is valid; remember them and don't try to run our own election for a bit
		raft.leaderId = appendEntriesMsgBody.LeaderId
		raft.resetElectionDeadline()

		// Check previous entry to see if it matches
		if appendEntriesMsgBody.PrevLogIndex <= 0 {
			return fmt.Errorf("out of bounds previous log index %d \n", appendEntriesMsgBody.PrevLogIndex)
		}

		if appendEntriesMsgBody.PrevLogIndex >= len(raft.log.Entries) ||
			(raft.log.get(appendEntriesMsgBody.PrevLogIndex).Term != appendEntriesMsgBody.PrevLogTerm) {
			// We disagree on the previous term
			raft.net.reply(msg, result)
			return nil
		}

		// We agree on the previous log term; truncate and append
		raft.log.truncate(appendEntriesMsgBody.PrevLogIndex)
		raft.log.append(appendEntriesMsgBody.Entries)

		// Advance commit pointer
		if raft.commitIndex < appendEntriesMsgBody.LeaderCommit {
			raft.commitIndex = min(appendEntriesMsgBody.LeaderCommit, raft.log.size())
		}

		// Acknowledge
		result.Success = true
		raft.net.reply(msg, result)
		return nil
	}

	if err := raft.net.on(structs.MsgTypeAppendEntries, appendEntries); err != nil {
		return err
	}

	// Handle Client KV requests
	kvRequests := func(msg structs.Msg, op structs.Operation) error {
		log.Println()
		if raft.state == StateLeader {
			// Record who we should tell about the completion of this Op
			op.Client = msg.Src
			raft.log.append([]structs.Entry{{
				Term: raft.currentTerm,
				Op:   op,
			}})
		} else if raft.leaderId != "" {
			// We're not the leader, but we can proxy To one
			msg.Dest = raft.leaderId
			raft.net.sendMsg(msg)
		} else {
			raft.net.reply(msg, structs.ErrorMsgBody{
				Type: structs.MsgTypeError,
				Code: 11,
				Text: "not a leader",
			})
		}
		return nil
	}

	kvReadRequest := func(msg structs.Msg) error {
		var readMsgBody structs.ReadMsgBody
		err := mapstructure.Decode(msg.Body, &readMsgBody)
		if err != nil {
			panic(err)
		}

		return kvRequests(msg, structs.Operation{
			Type:   readMsgBody.Type,
			MsgId:  readMsgBody.MsgId,
			Key:    readMsgBody.Key,
			Client: readMsgBody.Client,
		})
	}

	kvWriteRequest := func(msg structs.Msg) error {
		var writeMsgBody structs.WriteMsgBody
		err := mapstructure.Decode(msg.Body, &writeMsgBody)
		if err != nil {
			panic(err)
		}

		return kvRequests(msg, structs.Operation{
			Type:   writeMsgBody.Type,
			MsgId:  writeMsgBody.MsgId,
			Key:    writeMsgBody.Key,
			Client: writeMsgBody.Client,
			Value:  writeMsgBody.Value,
		})
	}

	kvCasRequest := func(msg structs.Msg) error {
		var casMsgBody structs.CasMsgBody
		err := mapstructure.Decode(msg.Body, &casMsgBody)
		if err != nil {
			panic(err)
		}

		return kvRequests(msg, structs.Operation{
			Type:   casMsgBody.Type,
			MsgId:  casMsgBody.MsgId,
			Key:    casMsgBody.Key,
			Client: casMsgBody.Client,
			From:   casMsgBody.From,
			To:     casMsgBody.From,
		})
	}

	if err := raft.net.on(structs.MsgTypeRead, kvReadRequest); err != nil {
		return err
	}
	if err := raft.net.on(structs.MsgTypeWrite, kvWriteRequest); err != nil {
		return err
	}
	if err := raft.net.on(structs.MsgTypeCas, kvCasRequest); err != nil {
		return err
	}
	return nil
}
