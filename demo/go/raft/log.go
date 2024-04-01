package main

import (
	"fmt"
	"github.com/samber/lo"
)

type Entry struct {
	Term int
	Op   MsgBody
}

type Log struct {
	Entries []Entry
}

func (log *Log) init() {
	log.Entries = []Entry{{
		Term: 0,
	}}
}

func (log *Log) get(index int) Entry {
	return log.Entries[index-1]
}

func (log *Log) append(entries []Entry) {
	// Appends multiple Entries To the log
	log.Entries = append(log.Entries, entries...)
	//logger.Println("append: entries", entries)
}

func (log *Log) last() Entry {
	// Returns the most recent entry
	return log.Entries[len(log.Entries)-1]
}

func (log *Log) lastTerm() int {
	// What's the Term of the last entry in the log?
	lastEntry := log.last()
	return lastEntry.Term
	// return 0
}

func (log *Log) size() int {
	return len(log.Entries)
}

func (log *Log) truncate(size int) {
	log.Entries = lo.Slice(log.Entries, 0, size)
}

func (log *Log) fromIndex(index int) ([]Entry, error) {
	if index <= 0 {
		return nil, fmt.Errorf("illegal index %d", index)
	}

	return lo.Slice(log.Entries, index, len(log.Entries)+1), nil
}

func newLog() *Log {
	log := Log{}
	log.init()
	return &log
}
