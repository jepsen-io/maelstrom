package main

import (
	"encoding/json"
	"fmt"
	maelstrom "github.com/jepsen-io/maelstrom/demo/go"
	"github.com/samber/lo"
	"golang.org/x/exp/maps"
	"golang.org/x/exp/rand"
	"log"
	"sync"
	"time"
)

const (
	StateCandidate = "candidate"
	StateFollower  = "follower"
	StateLeader    = "leader"
)

type RaftNode struct {
	electionTimeout        time.Duration
	heartbeatInterval      time.Duration
	minReplicationInterval time.Duration

	electionDeadline int64
	stepDownDeadline int64
	lastReplication  int64

	// Raft State
	state       string
	currentTerm int
	votedFor    string

	// Leader state
	nextIndex   map[string]int
	matchIndex  map[string]int
	commitIndex int
	lastApplied int
	leaderId    string

	// Components
	log          *Log
	node         *maelstrom.Node
	stateMachine *KVStore

	// Concurrency Locks
	becomeCandidateMu       sync.Mutex
	becomeFollowerMu        sync.Mutex
	advanceTermMu           sync.Mutex
	resetElectionDeadlineMu sync.Mutex
	requestVotesMu          sync.Mutex
	requestVoteResHandlerMu sync.Mutex
	maybeStepDownMu         sync.Mutex
	becomeLeaderMu          sync.Mutex
	replicateLogMu          sync.Mutex
	appendEntriesResMu      sync.Mutex
	resetStepDownDeadlineMu sync.Mutex
	advanceCommitIndexMu    sync.Mutex
}

func (raft *RaftNode) init() error {
	// Heartbeats & timeouts
	raft.electionTimeout = 2 * time.Second              // Time before election, in seconds
	raft.heartbeatInterval = 1 * time.Second            // Time between heartbeats, in seconds
	raft.minReplicationInterval = 50 * time.Millisecond // Don't replicate TOO frequently

	raft.electionDeadline = time.Now().UnixNano() // Next election, in epoch seconds
	raft.stepDownDeadline = time.Now().UnixNano() // When To step down automatically
	raft.lastReplication = time.Now().UnixNano()  // Last replication, in epoch seconds

	// Raft State
	raft.state = StateFollower
	raft.currentTerm = 0
	raft.votedFor = ""
	raft.leaderId = "" // Who do we think the leader is?

	// Leader State
	raft.commitIndex = 0
	raft.lastApplied = 1 // index: 0 -> Op: None
	raft.nextIndex = map[string]int{}
	raft.matchIndex = map[string]int{}

	// Components
	raft.log = newLog()
	raft.node = maelstrom.NewNode()
	raft.stateMachine = newKVStore()
	if err := raft.setupHandlers(); err != nil {
		return err
	}

	// random seed
	rand.Seed(uint64(time.Now().UnixNano()))
	return nil
}

func (raft *RaftNode) otherNodes() []string {
	// All nodes except this one
	return lo.Filter(raft.node.NodeIDs(), func(nodeId string, _ int) bool {
		return nodeId != raft.node.ID()
	})
}

func (raft *RaftNode) getMatchIndex() map[string]int {
	// Returns the map of match indices, including an entry for ourselves, based on our log size.
	clonedMap := maps.Clone(raft.matchIndex)
	clonedMap[raft.node.ID()] = raft.log.size()
	return clonedMap
}

func (raft *RaftNode) brpc(body map[string]interface{}, handler maelstrom.HandlerFunc) {
	// Broadcast an RPC message To all other nodes, and call handler with each response.
	log.Printf("in brpc otherNodes %v\n", raft.otherNodes())
	for _, nodeId := range raft.otherNodes() {
		raft.node.RPC(nodeId, body, handler)
	}
}

func (raft *RaftNode) resetElectionDeadline() {
	raft.resetElectionDeadlineMu.Lock()
	defer raft.resetElectionDeadlineMu.Unlock()
	temp := time.Duration(rand.Float64()+1.0) * time.Second
	log.Printf("resetElectionDeadline by seconds %d\n", temp)
	raft.electionDeadline = time.Now().UnixNano() + (temp + raft.electionTimeout).Nanoseconds()
}

func (raft *RaftNode) resetStepDownDeadline() {
	raft.resetStepDownDeadlineMu.Lock()
	defer raft.resetStepDownDeadlineMu.Unlock()
	// Don't step down for a while.
	raft.stepDownDeadline = time.Now().UnixNano() + (raft.electionTimeout).Nanoseconds()
}

func (raft *RaftNode) advanceTerm(term int) {
	raft.advanceTermMu.Lock()
	defer raft.advanceTermMu.Unlock()
	// Advance our Term to `Term`, resetting who we voted for.
	if raft.currentTerm >= term {
		panic(fmt.Errorf("Can't go backwards"))
	}

	raft.currentTerm = term
	raft.votedFor = ""
}

func (raft *RaftNode) maybeStepDown(remoteTerm int) {
	raft.maybeStepDownMu.Lock()
	defer raft.maybeStepDownMu.Unlock()
	// If remoteTerm is bigger than ours, advance our term and become a follower.
	if raft.currentTerm < remoteTerm {
		log.Printf("Stepping down: remote term %d higher than our term %d", remoteTerm, raft.currentTerm)
		raft.advanceTerm(remoteTerm)
		raft.becomeFollower()
	}
}

func (raft *RaftNode) requestVotes() {
	raft.requestVotesMu.Lock()
	defer raft.requestVotesMu.Unlock()
	// Request that other nodes vote for us as a leader

	votes := map[string]bool{}
	term := raft.currentTerm

	// We vote for our-self
	votes[raft.node.ID()] = true

	handler := func(msg maelstrom.Message) error {
		raft.requestVoteResHandlerMu.Lock()
		defer raft.requestVoteResHandlerMu.Unlock()
		raft.resetStepDownDeadline()
		var requestVoteResMsgBody RequestVoteResMsgBody
		if err := json.Unmarshal(msg.Body, &requestVoteResMsgBody); err != nil {
			panic(err)
		}

		raft.maybeStepDown(requestVoteResMsgBody.Term)

		if raft.state == StateCandidate &&
			raft.currentTerm == term &&
			requestVoteResMsgBody.Term == raft.currentTerm &&
			requestVoteResMsgBody.VotedGranted {

			// We have a vote for our candidacy
			votes[msg.Src] = true
			log.Println("have votes " + fmt.Sprint(votes))

			if majority(len(raft.node.NodeIDs())) <= len(votes) {
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
			"type":           MsgTypeRequestVote,
			"term":           raft.currentTerm,
			"candidate_id":   raft.node.ID(),
			"last_log_index": raft.log.size(),
			"last_log_term":  raft.log.lastTerm(),
		},
		handler,
	)
}

func (raft *RaftNode) becomeLeader() error {
	raft.becomeLeaderMu.Lock()
	defer raft.becomeLeaderMu.Unlock()
	if raft.state != StateCandidate {
		return fmt.Errorf("should be a candidate")
	}

	raft.state = StateLeader
	raft.leaderId = ""
	raft.lastReplication = 0 // Start replicating immediately

	// We'll start by trying To replicate our most recent entry
	raft.matchIndex = map[string]int{}
	raft.nextIndex = map[string]int{}
	for _, nodeId := range raft.otherNodes() {
		raft.nextIndex[nodeId] = raft.log.size() + 1
		raft.matchIndex[nodeId] = 0
	}
	raft.resetStepDownDeadline()
	log.Println("Became leader for term", raft.currentTerm)
	return nil
}

func (raft *RaftNode) becomeCandidate() {
	raft.becomeCandidateMu.Lock()
	defer raft.becomeCandidateMu.Unlock()
	raft.state = StateCandidate
	raft.advanceTerm(raft.currentTerm + 1)
	raft.votedFor = raft.node.ID()
	raft.leaderId = ""
	raft.resetElectionDeadline()
	raft.resetStepDownDeadline()
	log.Println("Became candidate for term", raft.currentTerm)
	raft.requestVotes()
}

func (raft *RaftNode) becomeFollower() {
	raft.becomeFollowerMu.Lock()
	defer raft.becomeFollowerMu.Unlock()

	raft.state = StateFollower
	raft.nextIndex = nil
	raft.matchIndex = nil
	raft.leaderId = ""
	raft.resetElectionDeadline()
	log.Println("Became follower for term", raft.currentTerm)
}

func (raft *RaftNode) advanceCommitIndex() {
	// If we're the leader, advance our commit index based on what other nodes match us.
	raft.advanceCommitIndexMu.Lock()
	defer raft.advanceCommitIndexMu.Unlock()
	if raft.state == StateLeader {
		n := median(maps.Values(raft.getMatchIndex()))
		if raft.commitIndex < n && raft.log.get(n).Term == raft.currentTerm {
			log.Printf("commit index now %d\n", n)
			raft.commitIndex = n
		}
	}
	raft.advanceStateMachine()
}

func (raft *RaftNode) advanceStateMachine() {
	// If we have un-applied committed Entries in the log, apply one to the state machine.
	for raft.lastApplied < raft.commitIndex {
		// Advance the applied index and apply that Op
		raft.lastApplied += 1
		entry := raft.log.get(raft.lastApplied)
		response := raft.stateMachine.apply(*entry.Op)
		if raft.state == StateLeader {
			// We were the leader, let's respond To the Client.
			if err := raft.node.Reply(entry.Msg, response); err != nil {
				panic(err)
			}
		}
	}
}

func newRaftNode() (*RaftNode, error) {
	raft := RaftNode{}
	if err := raft.init(); err != nil {
		return nil, err
	}

	// periodically try becoming candidate, if election deadline is passed and not a leader.
	becomeCandidateTicker := time.NewTicker(100 * time.Millisecond)
	go func() {
		for {
			select {
			case <-becomeCandidateTicker.C:
				r := rand.Int63n(100)
				time.Sleep(time.Duration(r) * time.Millisecond)
				if raft.electionDeadline < time.Now().UnixNano() {
					if raft.state != StateLeader {
						raft.becomeCandidate()
					} else {
						raft.resetElectionDeadline()
					}
				}
			}
		}
	}()

	// periodically step down if no acknowledgments are received
	leaderStepDownTicker := time.NewTicker(100 * time.Millisecond)
	go func() {
		for {
			select {
			case <-leaderStepDownTicker.C:
				if raft.state == StateLeader && raft.stepDownDeadline < time.Now().UnixNano() {
					log.Println("Stepping down: haven't received any acks recently")
					raft.becomeFollower()
				}
			}
		}
	}()

	// periodically replicate log
	replicateLogTicker := time.NewTicker(raft.minReplicationInterval)
	go func() {
		for {
			select {
			case <-replicateLogTicker.C:
				if err := raft.replicateLog(); err != nil {
					panic(err)
				}
			}
		}
	}()

	return &raft, nil
}
