package main

func main() {
	raft, err := newRaftNode()
	if err != nil {
		panic(err)
	}
	raft.main()
}
