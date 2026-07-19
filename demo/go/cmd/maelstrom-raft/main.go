package main

func main() {
	raft, err := newRaftNode()
	if err != nil {
		panic(err)
	}

	if err := raft.node.Run(); err != nil {
		panic(err)
	}
}
