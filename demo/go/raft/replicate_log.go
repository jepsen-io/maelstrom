package main

import (
	"fmt"
	"log"
	"time"
)

func (raft *RaftNode) replicateLog() (bool, error) {
	// If we're the leader, replicate unacknowledged log Entries to followers. Also serves as a heartbeat.

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
			ni := raft.nextIndex[nodeId]
			entries, err := raft.log.fromIndex(ni)
			if err != nil {
				return false, err
			}

			if len(entries) > 0 || raft.heartbeatInterval < elapsedTime {
				log.Printf("replicating %d to %s\n", ni, nodeId)

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
							log.Printf("node %s entries %d ni %d\n", _nodeId, len(_entries), ni)
							log.Println("next index:" + fmt.Sprint(raft.nextIndex))
						} else {
							raft.nextIndex[_nodeId] -= 1
						}
					}

					return nil
				}

				raft.net.rpc(
					nodeId,
					map[string]interface{}{
						"type":           appendEntriesMsgType,
						"term":           raft.currentTerm,
						"leader_id":      raft.nodeId,
						"prev_log_index": ni - 1,
						"prev_log_term":  raft.log.get(ni - 1).Term,
						"entries":        entries,
						"leader_commit":  raft.commitIndex,
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
