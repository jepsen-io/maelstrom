package main

import (
	"math"
	"sort"
)

func majority(n int) int {
	return int(math.Float64bits((float64(n) / 2.0) + 1))
}

func median(keys []int) int {
	sort.Ints(keys)
	return keys[len(keys)-majority(len(keys))]
}
