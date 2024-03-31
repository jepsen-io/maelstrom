package main

import (
	"fmt"
	"golang.org/x/exp/maps"
	"log"
	"math/rand"
	"os"
	"os/signal"
	"time"

	"github.com/samber/lo"
)

const (
	StateNascent   = "nascent"
	StateCandidate = "candidate"
	StateFollower  = "follower"
	StateLeader    = "leader"
)

type RaftNode struct {
	electionTimeout        int64
	heartbeatInterval      float64
	minReplicationInterval float64
	electionDeadline       int64
	stepDownDeadline       int64
	lastReplication        int64

	// Node and cluster IDs
	nodeId  string
	nodeIds []string

	// Raft State
	state       string
	currentTerm int
	votedFor    string
	commitIndex int
	lastApplied int
	leaderId    string

	// Leader state
	nextIndex  map[string]int
	matchIndex map[string]int

	// Components
	log          *Log
	net          *Net
	stateMachine *KVStore
}

func (raft *RaftNode) init() error {
	// Heartbeats & timeouts
	raft.electionTimeout = 2           // Time before election, in seconds
	raft.heartbeatInterval = 1         // Time between heartbeats, in seconds
	raft.minReplicationInterval = 0.05 // Don't replicate TOO frequently
	raft.electionDeadline = 0          // Next election, in epoch seconds
	raft.stepDownDeadline = 0          // When To step down automatically
	raft.lastReplication = 0           // Last replication, in epoch seconds

	// Node & cluster IDs
	raft.nodeId = ""
	raft.nodeIds = []string{}

	// Raft State
	raft.state = StateNascent
	raft.currentTerm = 0

	// Leader State
	raft.nextIndex = map[string]int{}
	raft.matchIndex = map[string]int{}
	raft.leaderId = "" // Who do we think the leader is?

	// Components
	raft.log = newLog()
	raft.net = newNet()
	raft.stateMachine = newKVStore()
	if err := raft.setupHandlers(); err != nil {
		return err
	}

	return nil
}

func (raft *RaftNode) otherNodes() []string {
	// All nodes except this one
	return lo.Filter(raft.nodeIds, func(nodeId string, _ int) bool {
		return nodeId != raft.nodeId
	})
}

func (raft *RaftNode) getMatchIndex() map[string]int {
	// Returns the map of match indices, including an entry for ourselves, based on our log size.
	clonedMap := maps.Clone(raft.matchIndex)
	clonedMap[raft.nodeId] = raft.log.size()
	return clonedMap
}

func (raft *RaftNode) setNodeId(id string) {
	raft.nodeId = id
	raft.net.setNodeId(id)
}

func (raft *RaftNode) brpc(body MsgBody, handler MsgHandler) {
	// Broadcast an RPC message To all other nodes, and call handler with each response.
	for _, nodeId := range raft.otherNodes() {
		raft.net.rpc(nodeId, body, handler)
	}
}

func (raft *RaftNode) resetElectionDeadline() {
	temp := int64(float64(raft.electionTimeout) * (rand.Float64() + 1.0))
	raft.electionDeadline = time.Now().Unix() + temp
}

func (raft *RaftNode) resetStepDownDeadline() {
	// Don't step down for a while.
	raft.stepDownDeadline = time.Now().Unix() + raft.electionTimeout
}

func (raft *RaftNode) advanceTerm(term int) error {
	// Advance our Term To `Term`, resetting who we voted for.
	if raft.currentTerm >= term {
		return fmt.Errorf("Can't go backwards")
	}

	raft.currentTerm = term
	raft.votedFor = ""
	return nil
}

func (raft *RaftNode) maybeStepDown(remoteTerm int) error {
	// If remoteTerm is bigger than ours, advance our Term and become a follower.
	if raft.currentTerm < remoteTerm {
		log.Printf("Stepping down: remote Term %d higher than our Term %d", remoteTerm, raft.currentTerm)
		if err := raft.advanceTerm(remoteTerm); err != nil {
			return err
		}
		raft.becomeFollower()
	}
	return nil
}

func (raft *RaftNode) requestVotes() error {
	// Request that other nodes vote for us as a leader

	votes := map[string]bool{}
	term := raft.currentTerm

	// We vote for our-self
	votes[raft.nodeId] = true

	handle := func(res Msg) error {
		raft.resetStepDownDeadline()
		body := res.Body
		if err := raft.maybeStepDown(body.Term); err != nil {
			return err
		}

		if raft.state == StateCandidate &&
			raft.currentTerm == term &&
			body.Term == raft.currentTerm &&
			body.VotedGranted {

			// We have a vote for our candidacy
			votes[res.Src] = true
			log.Println("have votes " + fmt.Sprint(votes))

			if majority(len(raft.nodeIds)) <= len(votes) {
				if err := raft.becomeLeader(); err != nil {
					return err
				}
			}
		}
		return nil
	}

	// Broadcast vote request
	raft.brpc(
		MsgBody{
			Type:         requestVoteMsgType,
			Term:         raft.currentTerm,
			CandidateId:  raft.nodeId,
			LastLogIndex: raft.log.size(),
			LastLogTerm:  raft.log.lastTerm(),
		},
		handle,
	)
	return nil
}

func (raft *RaftNode) becomeFollower() {
	raft.state = StateFollower
	raft.nextIndex = map[string]int{}
	raft.matchIndex = map[string]int{}
	raft.leaderId = ""
	raft.resetElectionDeadline()
	log.Println("Became follower for Term", raft.currentTerm)
}

func (raft *RaftNode) becomeCandidate() error {
	raft.state = StateCandidate
	if err := raft.advanceTerm(raft.currentTerm + 1); err != nil {
		return err
	}
	raft.votedFor = raft.nodeId
	raft.leaderId = ""
	raft.resetElectionDeadline()
	raft.resetStepDownDeadline()
	log.Println("Became candidate for Term", raft.currentTerm)
	if err := raft.requestVotes(); err != nil {
		return err
	}
	return nil
}

func (raft *RaftNode) becomeLeader() error {
	if raft.state != StateCandidate {
		return fmt.Errorf("should be a candidate")
	}

	raft.state = StateLeader
	raft.leaderId = ""
	raft.lastReplication = 0 // Start replicating immediately
	// We'll start by trying To replicate our most recent entry
	nextIndex := map[string]int{}
	matchIndex := map[string]int{}
	for _, nodeId := range raft.otherNodes() {
		nextIndex[nodeId] = raft.log.size() + 1
		matchIndex[nodeId] = 0
	}
	raft.resetStepDownDeadline()
	log.Println("became leader for Term", raft.currentTerm)
	return nil
}

func (raft *RaftNode) stepDownOnTimeout() (bool, error) {
	// If we haven't received any acks for a while, step down.
	if raft.state == StateLeader && raft.stepDownDeadline < time.Now().Unix() {
		log.Println("Stepping down: haven't received any acks recently")
		raft.becomeFollower()
		return true, nil
	}
	return false, nil
}

func (raft *RaftNode) replicateLog() (bool, error) {
	// If we're the leader, replicate unacknowledged log Entries To followers. Also serves as a heartbeat.

	// How long has it been since we replicated?
	elapsedTime := float64(time.Now().Unix() - raft.lastReplication)
	// We'll set this To true if we replicate To anyone
	replicated := false
	// We'll need this To make sure we process responses in *this* Term
	term := raft.currentTerm

	if raft.state == StateLeader && raft.minReplicationInterval < elapsedTime {
		// We're a leader, and enough time elapsed
		for _, nodeId := range raft.otherNodes() {
			// What Entries should we send this node?
			ni := raft.nextIndex[raft.nodeId]
			entries, err := raft.log.fromIndex(ni)
			if err != nil {
				return false, err
			}

			if len(entries) > 0 || raft.heartbeatInterval < elapsedTime {
				log.Printf("replicating %d + To %d\n", ni, nodeId)

				// closure
				_ni := ni
				_entries := append([]Entry(nil), entries...)
				_nodeId := nodeId

				handler := func(res Msg) error {
					body := res.Body
					if err := raft.maybeStepDown(body.Term); err != nil {
						return err
					}
					if raft.state == StateLeader && term == raft.currentTerm {
						raft.resetStepDownDeadline()
						if body.Success {
							raft.nextIndex[_nodeId] = max(raft.nextIndex[_nodeId], _ni+len(_entries))
							raft.matchIndex[_nodeId] = max(raft.matchIndex[_nodeId], _ni-1+len(_entries))
						} else {
							raft.nextIndex[_nodeId] -= 1
						}
					}

					return nil
				}

				raft.net.rpc(
					nodeId,
					MsgBody{
						Type:         "append_entries",
						Term:         raft.currentTerm,
						LeaderId:     raft.nodeId,
						PrevLogIndex: ni - 1,
						PrevLogTerm:  raft.log.get(ni - 1).term,
						Entries:      entries,
						LeaderCommit: raft.commitIndex,
					},
					handler,
				)
				replicated = true
			}
		}
	}

	if replicated {
		raft.lastReplication = time.Now().Unix()
		return true, nil
	}
	return false, nil
}

func (raft *RaftNode) election() (bool, error) {
	// If it's been long enough, trigger a leader election.
	if raft.electionDeadline < time.Now().Unix() {
		if (raft.state == StateFollower) || (raft.state == StateCandidate) {
			return true, raft.becomeCandidate()
		} else {
			raft.resetElectionDeadline()
		}
		return true, nil
	}

	return false, nil
}

func (raft *RaftNode) advanceCommitIndex() (bool, error) {
	// If we're the leader, advance our commit index based on what other nodes match us.
	if raft.state == StateLeader {
		n := median(maps.Values(raft.getMatchIndex()))
		if raft.commitIndex < n && raft.log.get(n).term == raft.currentTerm {
			log.Printf("commit index now %d\n", n)
			raft.commitIndex = n
			return true, nil
		}
	}
	return false, nil
}

func (raft *RaftNode) advanceStateMachine() (bool, error) {
	// If we have un-applied committed Entries in the log, apply one To the state machine.
	if raft.lastApplied < raft.commitIndex {
		// Advance the applied index and apply that op
		raft.lastApplied += 1
		raft.log.get(raft.lastApplied)
		response := raft.stateMachine.apply(raft.log.get(raft.lastApplied).op)
		if raft.state == StateLeader {
			// We were the leader, let's respond To the Client.
			raft.net.send(response.Dest, response.Body)
		}
	}
	return true, nil
}

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
		raft.net.reply(msg, MsgBody{Type: initOkMsgBodyType})
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

		raft.net.reply(msg, MsgBody{
			Type:         requestVoteResultMsgType,
			Term:         raft.currentTerm,
			VotedGranted: grant,
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

		result := MsgBody{
			Type:    appendEntriesResultMsgType,
			Term:    raft.currentTerm,
			Success: false,
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

		if msg.Body.PrevLogIndex >= len(raft.log.Entries) {
			return nil
		}

		if msg.Body.PrevLogTerm != raft.log.get(msg.Body.PrevLogIndex).term {
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
		result.Success = true
		raft.net.reply(msg, result)
		return nil
	}

	if err := raft.net.on(appendEntriesMsgType, appendEntries); err != nil {
		return err
	}

	// Handle Client KV requests
	kvRequests := func(msg Msg) error {
		if raft.state == StateLeader {
			// Record who we should tell about the completion of this op
			op := msg.Body
			op.Client = msg.Src
			raft.log.append([]Entry{{
				term: 0,
				op:   msg.Body, // deep copy ?
			}})
		} else if raft.leaderId != "" {
			// We're not the leader, but we can proxy To one
			msg.Dest = raft.leaderId
			raft.net.sendMsg(msg)
		} else {
			raft.net.reply(msg, MsgBody{
				Type: errorMsgType,
				Code: 11,
				Text: "not a leader",
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

func (raft *RaftNode) main() {
	log.Println("Online.")

	// *************** Handle KeyboardInterrupt ******************
	// Create a channel To receive signals.
	c := make(chan os.Signal, 1)

	// Register the interrupt signal with the channel.
	signal.Notify(c, os.Interrupt)

	// Start a goroutine To listen for signals.
	go func() {
		for range c {
			log.Println("Aborted by interrupt!")
			os.Exit(1)
		}
	}()
	// *************** Handle KeyboardInterrupt ******************

	for {
		if success, err := raft.net.processMsg(); err != nil || success {
			if err != nil {
				log.Println("Error! processMsg", err)
			}
		} else if success, err := raft.stepDownOnTimeout(); err != nil || success {
			if err != nil {
				log.Println("Error! stepDownOnTimeout", err)
			}
		} else if success, err := raft.replicateLog(); err != nil || success {
			if err != nil {
				log.Println("Error! replicateLog", err)
			}
		} else if success, err := raft.election(); err != nil || success {
			if err != nil {
				log.Println("Error! election", err)
			}
		} else if success, err := raft.advanceCommitIndex(); err != nil || success {
			if err != nil {
				log.Println("Error! advanceCommitIndex", err)
			}
		} else if success, err := raft.advanceStateMachine(); err != nil || success {
			if err != nil {
				log.Println("Error! advanceStateMachine", err)
			}
		}

		time.Sleep(1 * time.Millisecond)
	}
}

func newRaftNode() (*RaftNode, error) {
	raft := RaftNode{}
	if err := raft.init(); err != nil {
		return nil, err
	}
	return &raft, nil
}
