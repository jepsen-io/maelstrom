module github.com/jepsen-io/maelstrom/demo/go/cmd/maelstrom-raft

go 1.21.0

require (
	github.com/jepsen-io/maelstrom/demo/go v0.0.0-20240408130303-0186f398f965
	github.com/samber/lo v1.39.0
	golang.org/x/exp v0.0.0-20240525044651-4c93da0ed11d
)

// Use local dependency
replace github.com/jepsen-io/maelstrom/demo/go => ../../
