# Raft Demo in Go with Maelstrom

This repository contains a demo of the Raft consensus algorithm implemented in Go, designed to run within the Maelstrom framework.

## Prerequisites

To run this demo, you will need the following installed on your system:

- Go (version 1.21.0 or later)
- Maelstrom (latest version)

## Getting Started

Follow these steps to build and run the Raft demo:

### 1. Clone the Repository

Clone the Maelstrom repository if you haven't already:

```shell
git clone https://github.com/jepsen-io/maelstrom.git
cd maelstrom
```

### 2. Build the Raft Demo

Navigate to the Raft demo directory and build the Go application:
```shell
(cd demo/go/cmd/maelstrom-raft/ && go build)
```

### 3. Run the Demo

Execute the following command to run the Raft demo using Maelstrom with the specified parameters:

```shell
./maelstrom test -w lin-kv --bin demo/go/cmd/maelstrom-raft/maelstrom-raft --node-count 3 --concurrency 4n --rate 40 --time-limit 60 --nemesis partition --nemesis-interval 10 --test-count 1
```

### Explanation of the Command

- `./maelstrom test` : Invokes the Maelstrom test runner.
- `-w lin-kv`: Specifies the workload to be linearizable key-value store.
- `--bin demo/go/cmd/maelstrom-raft/maelstrom-raft`: Points to the built Raft demo binary.
- `--node-count 3`: Sets the number of nodes in the Raft cluster to 3.
- `--concurrency 4n`: Sets the concurrency level to 4 operations per node.
- `--rate 30`: Sets the rate of operations per second.
- `--time-limit 60`: Sets the time limit for the test to 60 seconds.
- `--nemesis partition`: Introduces network partitions as the fault injection.
- `--nemesis-interval 10`: time in seconds between nemesis operations, on average.
` `--test-count 10`: Runs the test for 10 counts.