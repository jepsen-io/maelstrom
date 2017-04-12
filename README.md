# Maelstrom

Maelstrom is a toolkit to help in writing toy Raft implementations, as a part
of a distributed systems workshop by [aphyr](https://jepsen.io/training).

Maelstrom runs any binary as a Raft node. Nodes read "network" messages from
STDIN, and write messages to STDOUT. Maelstrom performs initial setup, routes
messages between nodes, and simulates client activity. It uses the Knossos
consistency checker to verify the simulated cluster's behavior is linearizable.

This allows students to write a Raft implementation in whatever language they
are most comfortable with, without having to worry about discovery, network
communication, daemonization, writing their own distributed test harness, and
so on.

## Protocol

Maelstrom nodes receive messages on STDIN, send messages on STDOUT, and log
debugging output on STDERR. Maelstrom nodes must not print anything that is not
a message to STDOUT. Maelstrom will log STDERR output to disk for you.


### Messages

Maelstrom speaks JSON messages, separated by newlines (`\n`). Nodes receive
messages on STDIN, and send messages by printing them to stdout. Each message
is a JSON object with the following mandatory keys:

```json
{"src"      A string identifying the node this message came from
 "dest"     A string identifying the node this message is to
 "body"     An object: the payload of the message}
```

Bodies have the following reserved keys:

```json
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

```json
{"type"     "raft_init"
 "msg_id"   An integer
 "node_id"  A string identifying this node
 "node_ids" An array of all node ids in the cluster, including this node
```

When initialization is complete, respond with:

```json
{"type"         "raft_init_ok"
 "in_reply_to"  The message ID of the raft_init request}
```


### Errors

Use errors to inform clients that their request could not be completed
satisfactorily. You may use additional keys to provide metadata about the
error.

```json
{"type"        "error"
 "code"        (optional)   An integer identifying the type of error
 "text"        (optional)   A string representation of the error
 "in_reply_to" (mandatory)  The msg_id of the request that caused the error}
```

Maelstrom defines the following error codes. You may also define your own,
above 100.

```json
# Network errors
0     The request timed out
1     The node a message was sent to does not exist

# Generic errors
10    The given operation is not supported

# Key-value errors
20   The given key does not exist
21   The given key already exists
22   A precondition (e.g. a compare-and-set comparison) failed
```


### Writes

Maelstrom will simulate client writes by sending messages like:

```json
{"type"     "write"
 "msg_id"   An integer
 "key"      A string: the key the client would like to write
 "value"    A string: the value the client would like to write}
```

Keys should be created if they do not already exist. Respond to writes by
returning:

```json
{"type"         "write_ok"
 "in_reply_to"  The msg_id of the write request}
```


### Reads

Maelstrom will simulate client reads by sending messages like:

```json
{"type"       "read"
 "msg_id"     An integer
 "key"        A string: the key the client would like to read}
```

Respond to reads by returning:

```json
{"type"         "read_ok"
 "in_reply_to"  The msg_id of the read request
 "value"        The string value of the 
```

If the key does not exist, return

```json
{"type"         "error"
 "in_reply_to"  The msg_id of the request
 "code"         20}
```


### Compare and Set

Maelstrom will simulate client compare-and-set operations by sending messages
like:

```json
{"type"     "cas"
 "msg_id"   An integer
 "key"      A string: the key the client would like to write
 "from"     A string: the value that the client expects to be present
 "to"       A string: the value to write if and only if the value is `from`}
```

If the current value of the given key is `from`, set the key's value to `to`, and return:

```json
{"type"         "cas_ok"
 "in_reply_to"  The msg_id of the request}
```

If the key does not exist, return

```json
{"type"         "error"
 "in_reply_to"  The msg_id of the request
 "code"         20}
```

If the current value is *not* `from`, return

```json
{"type"         "error"
 "in_reply_to"  The msg_id of the request
 "code"         22}
```


### Delete

Maelstrom will simulate client deletes by sending messages like:

```json
{"type"       "delete"
 "msg_id"     An integer
 "key"        A string: the key to delete}
```

Delete the key from your table, and return:

```json
{"type"         "delete_ok"
 "in_reply_to"  The msg_id of the request}
```

If the key does not exist, return:

```json
{"type"         "error"
 "in_reply_to"  The msg_id of the request
 "code"         20}
```

## License

Copyright © 2017 Kyle Kingsbury

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
