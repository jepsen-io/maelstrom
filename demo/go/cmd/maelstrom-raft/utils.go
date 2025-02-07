package main

import (
	"log"
	"math"
	"sort"
)

func majority(n int) int {
	log.Println("math.Floor(float64(n)/2.0) + 1", math.Floor(float64(n)/2.0)+1)
	return int(math.Floor(float64(n)/2.0) + 1)
}

func median(keys []int) int {
	sort.Ints(keys)
	return keys[len(keys)-majority(len(keys))]
}
