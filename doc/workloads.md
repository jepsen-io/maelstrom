# Workloads

A *workload* specifies the semantics of a distributed system: what
operations are performed, how clients submit requests to the system, what
those requests mean, what kind of responses are expected, which errors can
occur, and how to check the resulting history for safety.

For instance, the *broadcast* workload says that clients submit `broadcast`
messages to arbitrary servers, and can send a `read` request to obtain the
set of all broadcasted messages. Clients mix reads and broadcast operations
throughout the history, and at the end of the test, perform a final read
after allowing a brief period for convergence. To check broadcast histories,
Maelstrom looks to see how long it took for messages to be broadcast, and
whether any were lost.

This is a reference document, automatically generated from Maelstrom's source
code by running `lein run doc`. For each workload, it describes the general
semantics of that workload, what errors are allowed, and the structure of RPC
messages that you'll need to handle.
 

## Table of Contents

- [Broadcast](#workload-broadcast)
- [Echo](#workload-echo)
- [G-set](#workload-g-set)
- [Lin-kv](#workload-lin-kv)
- [Pn-counter](#workload-pn-counter)

## Workload: Broadcast 

A broadcast system. Essentially a test of eventually-consistent set
addition, but also provides an initial `topology` message to the cluster with
a set of neighbors for each node to use. 

### RPC: Topology! 

A topology message is sent at the start of the test, after initialization,
and informs the node of an optional network topology to use for broadcast.
The topology consists of a map of node IDs to lists of neighbor node IDs. 

Request:

```clj
{:type (eq "topology"),
 :topology {java.lang.String [java.lang.String]},
 :msg_id Int}
```

Response:

```clj
{:type (eq "topology_ok"),
 #schema.core.OptionalKey{:k :msg_id} Int,
 :in_reply_to Int}
```


### RPC: Broadcast! 

Sends a single message into the broadcast system, and requests that it be
broadcast to everyone. Nodes respond with a simple acknowledgement message. 

Request:

```clj
{:type (eq "broadcast"), :message Any, :msg_id Int}
```

Response:

```clj
{:type (eq "broadcast_ok"),
 #schema.core.OptionalKey{:k :msg_id} Int,
 :in_reply_to Int}
```


### RPC: Read 

Requests all messages present on a node. 

Request:

```clj
{:type (eq "read"), :msg_id Int}
```

Response:

```clj
{:type (eq "read_ok"),
 :messages [Any],
 #schema.core.OptionalKey{:k :msg_id} Int,
 :in_reply_to Int}
```



## Workload: Echo 

A simple echo workload: sends a message, and expects to get that same
message back. 

### RPC: Echo! 

Clients send `echo` messages to servers with an `echo` field containing an
arbitrary payload they'd like to have sent back. Servers should respond with
`echo_ok` messages containing that same payload. 

Request:

```clj
{:type (eq "echo"), :echo Any, :msg_id Int}
```

Response:

```clj
{:type (eq "echo_ok"),
 :echo Any,
 #schema.core.OptionalKey{:k :msg_id} Int,
 :in_reply_to Int}
```



## Workload: G-set 

A grow-only set workload: clients add elements to a set, and read the
current value of the set. 

### RPC: Add! 

Requests that a server add a single element to the set. Acknowledged by an
`add_ok` message. 

Request:

```clj
{:type (eq "add"), :element Any, :msg_id Int}
```

Response:

```clj
{:type (eq "add_ok"),
 #schema.core.OptionalKey{:k :msg_id} Int,
 :in_reply_to Int}
```


### RPC: Read 

Requests the current set of all elements. Servers respond with a message
containing an `elements` key, whose `value` is a JSON array of added
elements. 

Request:

```clj
{:type (eq "read"), :msg_id Int}
```

Response:

```clj
{:type (eq "read_ok"),
 :value [Any],
 #schema.core.OptionalKey{:k :msg_id} Int,
 :in_reply_to Int}
```



## Workload: Lin-kv 

A workload for a linearizable key-value store. 

### RPC: Read 

Reads the current value of a single key. Clients send a `read` request with
the key they'd like to observe, and expect a response with the current
`value` of that key. 

Request:

```clj
{:type (eq "read"), :key Any, :msg_id Int}
```

Response:

```clj
{:type (eq "read_ok"),
 :value Any,
 #schema.core.OptionalKey{:k :msg_id} Int,
 :in_reply_to Int}
```


### RPC: Write! 

Blindly overwrites the value of a key. Creates keys if they do not presently
exist. Servers should respond with a `read_ok` response once the write is
complete. 

Request:

```clj
{:type (eq "write"), :key Any, :value Any, :msg_id Int}
```

Response:

```clj
{:type (eq "write_ok"),
 #schema.core.OptionalKey{:k :msg_id} Int,
 :in_reply_to Int}
```


### RPC: Cas! 

Atomically compare-and-sets a single key: if the value of `key` is currently
`from`, sets it to `to`. Returns error 20 if the key doesn't exist, and 22 if
the `from` value doesn't match. 

Request:

```clj
{:type (eq "cas"), :key Any, :from Any, :to Any, :msg_id Int}
```

Response:

```clj
{:type (eq "cas_ok"),
 #schema.core.OptionalKey{:k :msg_id} Int,
 :in_reply_to Int}
```



## Workload: Pn-counter 

An eventually-consistent counter which supports increments and decrements.
Validates that the final read on each node has a value which is the sum of
all known (or possible) increments and decrements. 

### RPC: Add! 

Adds a (potentially negative) integer, called `delta`, to the counter.
Servers should respond with an `add_ok` message. 

Request:

```clj
{:type (eq "add"), :delta Int, :msg_id Int}
```

Response:

```clj
{:type (eq "add_ok"),
 #schema.core.OptionalKey{:k :msg_id} Int,
 :in_reply_to Int}
```


### RPC: Read 

Reads the current value of the counter. Servers respond with a `read_ok`
message containing a `value`, which should be the sum of all (known) added
deltas. 

Request:

```clj
{:type (eq "read"), :msg_id Int}
```

Response:

```clj
{:type (eq "read_ok"),
 :value Int,
 #schema.core.OptionalKey{:k :msg_id} Int,
 :in_reply_to Int}
```



