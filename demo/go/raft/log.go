package main

import (
	"fmt"
	"github.com/pavan/maelstrom/demo/go/raft/structs"
	"github.com/samber/lo"
)

type Log struct {
	Entries []structs.Entry
}

func (log *Log) init() {
	// Note that we provide a default entry here, which simplifies
	// some default cases involving empty logs.
	log.Entries = []structs.Entry{{
		Term: 0,
	}}
}

func (log *Log) get(index int) structs.Entry {
	// Return a log entry by index. Note that Raft's log is 1-indexed.
	return log.Entries[index-1]
}

func (log *Log) append(entries []structs.Entry) {
	// Appends multiple entries to the log
	log.Entries = append(log.Entries, entries...)
	//logger.Println("append: entries", entries)
}

func (log *Log) last() structs.Entry {
	// Returns the most recent entry
	return log.Entries[len(log.Entries)-1]
}

func (log *Log) lastTerm() float64 {
	// What's the term of the last entry in the log?
	return log.last().Term // TODO: if index exception return 0
}

func (log *Log) size() int {
	return len(log.Entries)
}

func (log *Log) truncate(size int) {
	// Truncate the log to this many entries
	log.Entries = lo.Slice(log.Entries, 0, size)
}

func (log *Log) fromIndex(index int) ([]structs.Entry, error) {
	if index <= 0 {
		panic(fmt.Errorf("illegal index %d", index))
		return nil, fmt.Errorf("illegal index %d", index)
	}

	return lo.Slice(log.Entries, index-1, len(log.Entries)+1), nil
}

func newLog() *Log {
	log := Log{}
	log.init()
	return &log
}
