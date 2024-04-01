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
	raft.votedFor = ""
	raft.commitIndex = 0
	raft.lastApplied = 1 // index: 0 -> Op: None
	raft.leaderId = ""   // Who do we think the leader is?

	// Leader State
	raft.nextIndex = map[string]int{}
	raft.matchIndex = map[string]int{}

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

func (raft *RaftNode) brpc(body map[string]interface{}, handler MsgHandler) {
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

	handler := func(res Msg) error {
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
				// We have a majority of votes for this Term
				if err := raft.becomeLeader(); err != nil {
					return err
				}
			}
		}
		return nil
	}

	// Broadcast vote request
	raft.brpc(
		map[string]interface{}{
			"type":           requestVoteMsgType,
			"term":           raft.currentTerm,
			"candidate":      raft.nodeId,
			"last_log_index": raft.log.size(),
			"last_log_term":  raft.log.lastTerm(),
		},
		handler,
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
	for _, nodeId := range raft.otherNodes() {
		raft.nextIndex[nodeId] = raft.log.size() + 1
		raft.matchIndex[nodeId] = 0
	}
	raft.resetStepDownDeadline()
	log.Println("Became leader for Term", raft.currentTerm)
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
		if raft.commitIndex < n && raft.log.get(n).Term == raft.currentTerm {
			log.Printf("commit index now %d\n", n)
			raft.commitIndex = n
			return true, nil
		}
	}
	return false, nil
}

func (raft *RaftNode) advanceStateMachine() (bool, error) {
	// If we have un-applied committed Entries in the log, apply one To the state machine.
	//log.Printf("advanceStateMachine -> lastApplied %d, commitIndex %d", raft.lastApplied, raft.commitIndex)
	if raft.lastApplied < raft.commitIndex {
		// Advance the applied index and apply that Op
		raft.lastApplied += 1
		response := raft.stateMachine.apply(raft.log.get(raft.lastApplied).Op)
		if raft.state == StateLeader {
			// We were the leader, let's respond To the Client.
			raft.net.send(response.Dest, response.Body)
		}
	}
	return true, nil
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
