package main

import (
	"fmt"
	"github.com/mitchellh/mapstructure"
	"github.com/pavan/maelstrom/demo/go/raft/structs"
	"log"
	"time"
)

func (raft *RaftNode) replicateLog() (bool, error) {
	// If we're the leader, replicate unacknowledged log entries to followers. Also serves as a heartbeat.

	// How long has it been since we replicated?
	elapsedTime := float64(time.Now().Unix() - raft.lastReplication)
	// We'll set this To true if we replicate To anyone
	replicated := false
	// We'll need this To make sure we process responses in *this* term
	term := raft.currentTerm

	if raft.state == StateLeader && raft.minReplicationInterval < elapsedTime {
		// We're a leader, and enough time elapsed
		for _, nodeId := range raft.otherNodes() {
			// What entries should we send this node?
			ni := raft.nextIndex[nodeId]
			entries, err := raft.log.fromIndex(ni)
			if err != nil {
				return false, err
			}

			if 0 < len(entries) || raft.heartbeatInterval < elapsedTime {
				log.Printf("replicating %d to %s\n", ni, nodeId)

				// closure
				_ni := ni
				_entries := append([]structs.Entry{}, entries...)
				_nodeId := nodeId

				appendEntriesResHandler := func(res structs.Msg) error {
					var appendEntriesResMsgBody structs.AppendEntriesResMsgBody
					err := mapstructure.Decode(res.Body, &appendEntriesResMsgBody)
					if err != nil {
						panic(err)
					}

					if err := raft.maybeStepDown(appendEntriesResMsgBody.Term); err != nil {
						return err
					}
					if raft.state == StateLeader && term == raft.currentTerm {
						raft.resetStepDownDeadline()
						if appendEntriesResMsgBody.Success {
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
					structs.AppendEntriesMsgBody{
						Type:         structs.MsgTypeAppendEntries,
						Term:         raft.currentTerm,
						LeaderId:     raft.nodeId,
						PrevLogIndex: ni - 1,
						PrevLogTerm:  raft.log.get(ni - 1).Term,
						Entries:      entries,
						LeaderCommit: raft.commitIndex,
					},
					appendEntriesResHandler,
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
