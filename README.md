# Maelstrom

Maelstrom is a toolkit to help in writing toy Raft implementations, as a part
of a distributed systems workshop by [aphyr](https://jepsen.io/training). I'm
still putting this class together, and I'm looking for feedback. If you have
comments, or would like me to teach this class at your organization, contact
[aphyr@jepsen.io](mailto:aphyr@jepsen.io).

Maelstrom runs any binary as a Raft node. Nodes read "network" messages from
STDIN, and write messages to STDOUT. Maelstrom performs initial setup, routes
messages between nodes, and simulates client activity. It uses the Knossos
consistency checker to verify the simulated cluster's behavior is linearizable.

This allows students to write a Raft implementation in whatever language they
are most comfortable with, without having to worry about discovery, network
communication, daemonization, writing their own distributed test harness, and
so on.

## Install

You'll need JDK8 or higher. Then [download the Maelstrom
JAR](https://github.com/jepsen-io/maelstrom/releases/download/0.1.0/maelstrom-0.1.0-standalone.jar), drop it anywhere, and you're good to go.

## Quickstart

Create a fresh directory to work in; Maelstrom creates some local files. You
can use a checkout of this repository, or make an empty directory: `mkdir
my-test`.

An example Ruby implementation is in [demo/raft.rb](demo/raft.rb). We can test
it by running:

```sh
$ java -jar maelstrom.jar test --bin demo/raft.rb
...
Everything looks good! ヽ(‘ー`)ノ
```

Maelstrom starts five copies of `raft.rb`, connects them via its simulated
network, constructs five clients to perform reads, writes, and compare-and-set
operations against the server, simulates randomized network partitions, and
finally, verifies whether the resulting history was linearizable.

Logs from each node are available in the local directory: `n1.log`, `n2.log`,
etc. Detailed results of the test are available in `store/latest/`:

- jepsen.log: The full log from the Maelstrom run
- history.txt: The logical history of the system, as seen by clients
- results.edn: Analysis results
- latency-raw.png: Raw operation latencies during the test. Grey regions
  indicate network partitions.

More detailed results for each key in the key-value store are available in
`store/latest/independent/<key>/`:

- results.edn: Results for that key in particular
- history.txt: The history of operations on that key
- timeline.html: HTML visualization of the history for that key
- linear.svg: interactive visualization of nonlinearizable anomalies, if found

You can launch a web server for browsing these results:

```sh
java -jar maelstrom.jar serve
22:41:00.605 [main] INFO  jepsen.web - Web server running.
22:41:00.608 [main] INFO  jepsen.cli - Listening on http://0.0.0.0:8080/
```

## Controlling tests

A full list of options is available by running `java -jar maelstrom.jar test
--help`. The important ones are:

- `--node NODE-NAME`: Specify node names by hand

To get more information, use:

- `--log-stderr`: Show STDERR output from each node in the Maelstrom log
- `--log-net-send`: Log messages as they are sent into the network
- `--log-net-recv`: Log messages as they are received by nodes

To make tests more or less aggressive, use:

- `--concurrency INT`: Number of clients to run concurrently
- `--rate FLOAT`: Approximate number of requests per second per client
- `--time-limit SECONDS`: How long to run tests for
- `--latency MILLIS`: Approximate simulated network latency, during normal
  operations.

SSH options are unused; Maelstrom runs entirely on the local node.

## Protocol

Maelstrom nodes receive messages on STDIN, send messages on STDOUT, and log
debugging output on STDERR. Maelstrom nodes must not print anything that is not
a message to STDOUT. Maelstrom will log STDERR output to disk for you.


### Messages

Maelstrom speaks JSON messages, separated by newlines (`\n`). Nodes receive
messages on STDIN, and send messages by printing them to stdout. Each message
is a JSON object with the following mandatory keys:

```edn
{"src"      A string identifying the node this message came from
 "dest"     A string identifying the node this message is to
 "body"     An object: the payload of the message}
```

Bodies have the following reserved keys:

```edn
{"type"           (mandatory) A string identifying the type of message this is
 "msg_id"         (optional)  A unique integer identifier
 "in_reply_to"    (optional)  For req/response, the msg_id of the request}
```

Message IDs should be unique to the node which generated them. For instance,
each node can use a monotonically increasing integer as their source of message
IDs.

Maelstrom defines the following body types:


### Raft Initialization

Maelstrom will send a single initialization message to each node when it
starts, telling the node what its ID is, and who the other nodes are. When you receive this message, initialize your internal Raft state.

```edn
{"type"     "init"
 "msg_id"   An integer
 "node_id"  A string identifying this node
 "node_ids" An array of all node ids in the cluster, including this node
```

When initialization is complete, respond with:

```edn
{"type"         "init_ok"
 "in_reply_to"  The message ID of the init request}
```


### Errors

Use errors to inform clients that their request could not be completed
satisfactorily. You may use additional keys to provide metadata about the
error.

```edn
{"type"        "error"
 "code"        (optional)   An integer identifying the type of error
 "text"        (optional)   A string representation of the error
 "in_reply_to" (mandatory)  The msg_id of the request that caused the error}
```

Maelstrom defines the following error codes. You may also define your own,
above 100.

```edn
# Network errors
0     The request timed out
1     The node a message was sent to does not exist

# Generic errors
10    The given operation is not supported
11    This node is temporarily unable to serve this type of request

# Key-value errors
20   The given key does not exist
21   The given key already exists
22   A precondition (e.g. a compare-and-set comparison) failed
```

Codes 1, 10, 11, 20, 21, and 22 are understood to be *definite* failures: they
indicate the request cannot have, and will never, succeed. Other error codes
are *indeterminate*: Maelstrom assumes they may or may not succeed.

Use code 10 when developing your server, as a stub to indicate failure. Use
code 11 for things like requests made to an uninitialized node, or a node which
is a follower.

### Writes

Maelstrom will simulate client writes by sending messages like:

```edn
{"type"     "write"
 "msg_id"   An integer
 "key"      A string: the key the client would like to write
 "value"    A string: the value the client would like to write}
```

Keys should be created if they do not already exist. Respond to writes by
returning:

```edn
{"type"         "write_ok"
 "in_reply_to"  The msg_id of the write request}
```


### Reads

Maelstrom will simulate client reads by sending messages like:

```edn
{"type"       "read"
 "msg_id"     An integer
 "key"        A string: the key the client would like to read}
```

Respond to reads by returning:

```edn
{"type"         "read_ok"
 "in_reply_to"  The msg_id of the read request
 "value"        The string value for that key
```

If the key does not exist, return

```edn
{"type"         "error"
 "in_reply_to"  The msg_id of the request
 "code"         20}
```


### Compare and Set

Maelstrom will simulate client compare-and-set operations by sending messages
like:

```edn
{"type"     "cas"
 "msg_id"   An integer
 "key"      A string: the key the client would like to write
 "from"     A string: the value that the client expects to be present
 "to"       A string: the value to write if and only if the value is `from`}
```

If the current value of the given key is `from`, set the key's value to `to`, and return:

```edn
{"type"         "cas_ok"
 "in_reply_to"  The msg_id of the request}
```

If the key does not exist, return

```edn
{"type"         "error"
 "in_reply_to"  The msg_id of the request
 "code"         20}
```

If the current value is *not* `from`, return

```edn
{"type"         "error"
 "in_reply_to"  The msg_id of the request
 "code"         22}
```


### Delete

Maelstrom will simulate client deletes by sending messages like:

```edn
{"type"       "delete"
 "msg_id"     An integer
 "key"        A string: the key to delete}
```

Delete the key from your table, and return:

```edn
{"type"         "delete_ok"
 "in_reply_to"  The msg_id of the request}
```

If the key does not exist, return:

```edn
{"type"         "error"
 "in_reply_to"  The msg_id of the request
 "code"         20}
```

## License

Copyright © 2017 Kyle Kingsbury

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
