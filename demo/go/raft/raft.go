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

func (raft *RaftNode) init() {
	// Heartbeats & timeouts
	raft.electionTimeout = 2           // Time before election, in seconds
	raft.heartbeatInterval = 1         // Time between heartbeats, in seconds
	raft.minReplicationInterval = 0.05 // Don't replicate TOO frequently
	raft.electionDeadline = 0          // Next election, in epoch seconds
	raft.stepDownDeadline = 0          // When to step down automatically
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
	raft.leaderId = ""

	// Components
	raft.log = newLog()
	raft.net = newNet()
	raft.stateMachine = newKVStore()
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
}

func (raft *RaftNode) brpc(body MsgBody, handler MsgHandler) {
	// Broadcast an RPC message to all other nodes, and call handler with each response.
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
	// Advance our term to `term`, resetting who we voted for.
	if raft.currentTerm >= term {
		return fmt.Errorf("can't go backwards")
	}

	raft.currentTerm = term
	raft.votedFor = ""
	return nil
}

func (raft *RaftNode) maybeStepDown(remoteTerm int) {
	// If remoteTerm is bigger than ours, advance our term and become a follower.
	if raft.currentTerm < remoteTerm {
		fmt.Printf("Stepping down: remote term %d higher than our term %d", remoteTerm, raft.currentTerm)
		raft.advanceTerm(remoteTerm)
		raft.becomeFollower()
	}
}

func (raft *RaftNode) requestVotes() error {
	// Request that other nodes vote for us as a leader

	votes := map[string]bool{}
	term := raft.currentTerm

	// We vote for our-self
	votes[raft.nodeId] = true

	handle := func(res Msg) {
		raft.resetStepDownDeadline()
		body := res.body
		raft.maybeStepDown(body.term)

		if raft.state == StateCandidate &&
			raft.currentTerm == term &&
			body.term == raft.currentTerm &&
			body.votedGranted {

			// We have a vote for our candidacy
			votes[res.src] = true
			log.Println("have votes " + fmt.Sprint(votes))

			if majority(len(raft.nodeIds)) <= len(votes) {
				raft.becomeLeader()
			}
		}
	}

	if entry, err := raft.log.last(); err != nil {
		return err
	} else {
		// Broadcast vote request
		raft.brpc(
			MsgBody{
				Type:         requestVoteMsgBodyType,
				term:         raft.currentTerm,
				candidateId:  raft.nodeId,
				lastLogIndex: raft.log.size(),
				lastLogTerm:  entry.term,
			},
			handle,
		)
	}
	return nil
}

func (raft *RaftNode) becomeFollower() {
	raft.state = StateFollower
	raft.nextIndex = map[string]int{}
	raft.matchIndex = map[string]int{}
	raft.leaderId = ""
	raft.resetElectionDeadline()
	log.Println("Became follower for term", raft.currentTerm)
}

func (raft *RaftNode) becomeCandidate() {
	raft.state = StateCandidate
	raft.advanceTerm(raft.currentTerm + 1)
	raft.votedFor = raft.nodeId
	raft.leaderId = ""
	raft.resetElectionDeadline()
	raft.resetStepDownDeadline()
	log.Println("Became candidate for term", raft.currentTerm)
	raft.requestVotes()
}

func (raft *RaftNode) becomeLeader() error {
	if raft.state != StateCandidate {
		return fmt.Errorf("should be a candidate")
	}

	raft.state = StateLeader
	raft.leaderId = ""
	raft.lastReplication = 0 // Start replicating immediately
	// We'll start by trying to replicate our most recent entry
	nextIndex := map[string]int{}
	matchIndex := map[string]int{}
	for _, nodeId := range raft.otherNodes() {
		nextIndex[nodeId] = raft.log.size() + 1
		matchIndex[nodeId] = 0
	}
	raft.resetStepDownDeadline()
	log.Println("Became leader for term", raft.currentTerm)
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
	// If we're the leader, replicate unacknowledged log entries to followers. Also serves as a heartbeat.

	// How long has it been since we replicated?
	elapsedTime := float64(time.Now().Unix() - raft.lastReplication)
	// We'll set this to true if we replicate to anyone
	replicated := false
	// We'll need this to make sure we process responses in *this* term
	term := raft.currentTerm

	if raft.state == StateLeader && raft.minReplicationInterval < elapsedTime {
		// We're a leader, and enough time elapsed
		for _, nodeId := range raft.otherNodes() {
			// What entries should we send this node?
			ni := raft.nextIndex[raft.nodeId]
			entries, err := raft.log.fromIndex(ni)
			if err != nil {
				return false, err
			}

			if len(entries) > 0 || raft.heartbeatInterval < elapsedTime {
				fmt.Printf("replicating %d + to %d\n", ni, nodeId)

				// closure
				_ni := ni
				_entries := append([]Entry(nil), entries...)
				_nodeId := nodeId

				handler := func(res Msg) {
					body := res.body
					raft.maybeStepDown(body.term)
					if raft.state == StateLeader && term == raft.currentTerm {
						raft.resetStepDownDeadline()
						if body.success {
							raft.nextIndex[_nodeId] = max(raft.nextIndex[_nodeId], _ni+len(_entries))
							raft.matchIndex[_nodeId] = max(raft.matchIndex[_nodeId], _ni-1+len(_entries))
						} else {
							raft.nextIndex[_nodeId] -= 1
						}
					}
				}

				raft.net.rpc(
					nodeId,
					MsgBody{
						Type:         "append_entries",
						term:         raft.currentTerm,
						leaderId:     raft.nodeId,
						prevLogIndex: ni - 1,
						prevLogTerm:  raft.log.get(ni - 1).term,
						entries:      entries,
						leaderCommit: raft.commitIndex,
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
			raft.becomeCandidate()
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
			fmt.Printf("commit index now %d\n", n)
			raft.commitIndex = n
			return true, nil
		}
	}
	return false, nil
}

func (raft *RaftNode) advanceStateMachine() (bool, error) {
	// If we have un-applied committed entries in the log, apply one to the state machine.
	if raft.lastApplied < raft.commitIndex {
		// Advance the applied index and apply that op
		raft.lastApplied += 1
		raft.log.get(raft.lastApplied)
		response := raft.stateMachine.apply(raft.log.get(raft.lastApplied).op)
		if raft.state == StateLeader {
			// We were the leader, let's respond to the client.
			raft.net.send(response.dest, response.body)
		}
	}
	return true, nil
}

func (raft *RaftNode) main() {
	log.Println("Online.")

	// *************** Handle KeyboardInterrupt ******************
	// Create a channel to receive signals.
	c := make(chan os.Signal, 1)

	// Register the interrupt signal with the channel.
	signal.Notify(c, os.Interrupt)

	// Start a goroutine to listen for signals.
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
				log.Println("Error!", err)
			}
		} else if success, err := raft.stepDownOnTimeout(); err != nil || success {
			if err != nil {
				log.Println("Error!", err)
			}
		} else if success, err := raft.replicateLog(); err != nil || success {
			if err != nil {
				log.Println("Error!", err)
			}
		} else if success, err := raft.election(); err != nil || success {
			if err != nil {
				log.Println("Error!", err)
			}
		} else if success, err := raft.advanceCommitIndex(); err != nil || success {
			if err != nil {
				log.Println("Error!", err)
			}
		} else if success, err := raft.advanceStateMachine(); err != nil || success {
			if err != nil {
				log.Println("Error!", err)
			}
		}

		time.Sleep(1 * time.Millisecond)
	}
}

func newRaftNode() *RaftNode {
	raft := RaftNode{}
	raft.init()
	return &raft
}
