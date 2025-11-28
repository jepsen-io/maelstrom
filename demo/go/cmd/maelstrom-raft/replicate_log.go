package main

import (
	"encoding/json"
	"fmt"
	maelstrom "github.com/jepsen-io/maelstrom/demo/go"
	"log"
	"time"
)

func (raft *RaftNode) replicateLog() error {
	raft.replicateLogMu.Lock()
	defer raft.replicateLogMu.Unlock()

	// If we're the leader, replicate unacknowledged log entries to followers. Also serves as a heartbeat.

	// How long has it been since we replicated?
	elapsedTime := time.Duration(time.Now().UnixNano() - raft.lastReplication)
	// We'll set this to true if we replicate to anyone
	replicated := false
	// We'll need this to make sure we process responses in *this* term
	term := raft.currentTerm

	if raft.state == StateLeader && raft.minReplicationInterval < elapsedTime {
		// We're a leader, and enough time elapsed
		for _, nodeId := range raft.otherNodes() {
			// What entries should we send this node?
			ni := raft.nextIndex[nodeId]
			entries := raft.log.fromIndex(ni)

			if 0 < len(entries) || raft.heartbeatInterval < elapsedTime {
				log.Printf("Replicating %d to %s\n", ni, nodeId)
				replicated = true

				// closure
				_ni := ni
				_entries := append([]Entry{}, entries...)
				_nodeId := nodeId

				appendEntriesResHandler := func(res maelstrom.Message) error {
					raft.appendEntriesResMu.Lock()
					defer raft.appendEntriesResMu.Unlock()

					var appendEntriesResMsgBody AppendEntriesResMsgBody
					err := json.Unmarshal(res.Body, &appendEntriesResMsgBody)
					if err != nil {
						panic(err)
					}

					raft.maybeStepDown(appendEntriesResMsgBody.Term)
					if raft.state == StateLeader && term == raft.currentTerm {
						raft.resetStepDownDeadline()
						if appendEntriesResMsgBody.Success {
							// Excellent, these entries are now replicated!
							raft.nextIndex[_nodeId] = max(raft.nextIndex[_nodeId], _ni+len(_entries))
							raft.matchIndex[_nodeId] = max(raft.matchIndex[_nodeId], _ni+len(_entries)-1)
							log.Printf("node %s entries %d ni %d\n", _nodeId, len(_entries), ni)
							log.Println("next index:" + fmt.Sprint(raft.nextIndex))
							raft.advanceCommitIndex()
						} else {
							raft.nextIndex[_nodeId] -= 1
						}
					}

					return nil
				}

				if err := raft.node.RPC(
					nodeId,
					&AppendEntriesMsgBody{
						Type:         MsgTypeAppendEntries,
						Term:         raft.currentTerm,
						LeaderId:     raft.node.ID(),
						PrevLogIndex: ni - 1,
						PrevLogTerm:  raft.log.get(ni - 1).Term,
						Entries:      entries,
						LeaderCommit: raft.commitIndex,
					},
					appendEntriesResHandler,
				); err != nil {
					panic(err)
				}
			}
		}
	}

	if replicated {
		raft.lastReplication = time.Now().UnixNano()
	}
	return nil
}
