package main

import (
	"fmt"
	"log"
)

func (raft *RaftNode) setupHandlers() error {
	// Handle initialization message
	raftInit := func(msg Msg) error {
		if raft.state != StateNascent {
			return fmt.Errorf("Can't init twice")
		}

		raft.setNodeId(msg.Body.NodeId)
		raft.nodeIds = msg.Body.NodeIds
		raft.becomeFollower()

		log.Println("I am: ", raft.nodeId)
		raft.net.reply(msg, map[string]interface{}{"type": initOkMsgBodyType})
		return nil
	}
	if err := raft.net.on(initMsgBodyType, raftInit); err != nil {
		return err
	}

	// When a node requests our vote...
	requestVote := func(msg Msg) error {
		if err := raft.maybeStepDown(msg.Body.Term); err != nil {
			return err
		}
		grant := false

		if msg.Body.Term < raft.currentTerm {
			log.Printf("candidate Term %d lower than %d not granting vote \n", msg.Body.Term, raft.currentTerm)
		} else if raft.votedFor != "" {
			log.Printf("already voted for %s not granting vote \n", raft.votedFor)
		} else if msg.Body.LastLogTerm < raft.log.lastTerm() {
			log.Printf("have log Entries From Term %d which is newer than remote Term %d not granting vote\n", raft.log.lastTerm(), msg.Body.LastLogTerm)
		} else if msg.Body.LastLogTerm == raft.log.lastTerm() && msg.Body.LastLogIndex < raft.log.size() {
			log.Printf("our logs are both at Term %d but our log is %d and theirs is only %d \n", raft.log.lastTerm(), raft.log.size(), msg.Body.LastLogIndex)
		} else {
			log.Printf("Granting vote To %s\n", msg.Src)
			grant = true
			raft.votedFor = msg.Body.CandidateId
			raft.resetElectionDeadline()
		}

		raft.net.reply(msg, map[string]interface{}{
			"type":         requestVoteResultMsgType,
			"term":         raft.currentTerm,
			"vote_granted": grant,
		})

		return nil
	}
	if err := raft.net.on(requestVoteMsgType, requestVote); err != nil {
		return err
	}

	appendEntries := func(msg Msg) error {
		if err := raft.maybeStepDown(msg.Body.Term); err != nil {
			return err
		}

		result := map[string]interface{}{
			"type":    appendEntriesResultMsgType,
			"term":    raft.currentTerm,
			"success": false,
		}

		if msg.Body.Term < raft.currentTerm {
			// leader is behind us
			raft.net.reply(msg, result)
			return nil
		}

		// This leader is valid; remember them and don't try To run our own election for a bit
		raft.leaderId = msg.Body.LeaderId
		raft.resetElectionDeadline()

		// Check previous entry To see if it matches
		if msg.Body.PrevLogIndex <= 0 {
			return fmt.Errorf("out of bounds previous log index %d \n", msg.Body.PrevLogIndex)
		}

		if msg.Body.PrevLogIndex < len(raft.log.Entries) && (msg.Body.PrevLogTerm != raft.log.get(msg.Body.PrevLogIndex).Term) {
			// We disagree on the previous Term
			raft.net.reply(msg, result)
			return nil
		}

		// We agree on the previous log Term; truncate and append
		raft.log.truncate(msg.Body.PrevLogIndex)
		raft.log.append(msg.Body.Entries)

		// Advance commit pointer
		if raft.commitIndex < msg.Body.LeaderCommit {
			raft.commitIndex = min(msg.Body.LeaderCommit, raft.log.size())
		}

		// Acknowledge
		result["success"] = true
		raft.net.reply(msg, result)
		return nil
	}

	if err := raft.net.on(appendEntriesMsgType, appendEntries); err != nil {
		return err
	}

	// Handle Client KV requests
	kvRequests := func(msg Msg) error {
		if raft.state == StateLeader {
			// Record who we should tell about the completion of this Op
			op := msg.Body
			op.Client = msg.Src
			raft.log.append([]Entry{{
				Term: raft.currentTerm,
				Op:   op,
			}})
		} else if raft.leaderId != "" {
			// We're not the leader, but we can proxy To one
			msg.Dest = raft.leaderId
			raft.net.sendMsg(msg)
		} else {
			raft.net.reply(msg, map[string]interface{}{
				"type": errorMsgType,
				"code": 11,
				"text": "not a leader",
			})
		}
		return nil
	}

	if err := raft.net.on(readMsgType, kvRequests); err != nil {
		return err
	}
	if err := raft.net.on(writeMsgType, kvRequests); err != nil {
		return err
	}
	if err := raft.net.on(casMsgType, kvRequests); err != nil {
		return err
	}
	return nil
}
