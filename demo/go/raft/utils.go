package main

import (
	"log"
	"math"
	"sort"
	"syscall"
)

func majority(n int) int {
	log.Printf("majority %d : return %d", n, int(math.Float64bits((float64(n)/2.0)+1)))
	return int(math.Float64bits((float64(n) / 2.0) + 1))
}

func median(keys []int) int {
	sort.Ints(keys)
	return keys[len(keys)-majority(len(keys))]
}

func FD_SET(p *syscall.FdSet, i int) {
	p.Bits[i/64] |= 1 << uint(i) % 64
}

func FD_ZERO(p *syscall.FdSet) {
	for i := range p.Bits {
		p.Bits[i] = 0
	}
}

func FD_ISSET(p *syscall.FdSet, i int) bool {
	return (p.Bits[i/64] & (1 << uint(i) % 64)) != 0
}
