package main

import (
	"fmt"
	"github.com/samber/lo"
)

type Entry struct {
	term int
	op   string
}

type Log struct {
	Entries []Entry
}

func (log *Log) init() {
	log.Entries = []Entry{{
		term: 0,
		op:   "",
	}}
}

func (log *Log) get(index int) Entry {
	return log.Entries[index-1]
}

func (log *Log) append(entries []Entry) {
	// Appends multiple entries to the log.
	log.Entries = append(log.Entries, entries...)
}

func (log *Log) last() (Entry, error) {
	// Returns the most recent entry
	return lo.Last(log.Entries)
}

func (log *Log) lastTerm() int {
	// What's the term of the last entry in the log?
	lastEntry, err := log.last()
	if err == nil {
		return lastEntry.term
	}

	return 0
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
