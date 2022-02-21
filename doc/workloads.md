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
- [G-counter](#workload-g-counter)
- [G-set](#workload-g-set)
- [Lin-kv](#workload-lin-kv)
- [Pn-counter](#workload-pn-counter)
- [Txn-list-append](#workload-txn-list-append)

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



## Workload: G-counter 

An eventually-consistent grow-only counter, which supports increments.
Validates that the final read on each node has a value which is the sum of
all known (or possible) increments.

See also: pn-counter, which is identical, but allows decrements. 

### RPC: Add! 

Adds a non-negative integer, called `delta`, to the counter. Servers should
respond with an `add_ok` message. 

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
exist. Servers should respond with a `write_ok` response once the write is
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

See also: g-counter, which is identical, but does not allow decrements. 

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



## Workload: Txn-list-append 

A transactional workload over a map of keys to lists of elements. Clients
submit a single transaction per request via a `txn` request, and expect a
completed version of that transaction in a `txn_ok` response.

A transaction is an array of micro-operations, which should be executed in
order:

```edn
[op1, op2, ...]
```

Each micro-op is a 3-element array comprising a function, key, and value:

```edn
[f, k, v]
```

There are two functions. A *read* observes the current value of a specific
key. `["r", 5, [1, 2]]` denotes that a read of key 5 observed the list `[1,
2]`. When clients submit reads, they leave their values `null`:  `["r", 5,
null]`. The server processing the transaction should replace that value with
whatever the observed value is for that key: `["r", 5, [1, 2]]`.

An *append* adds an element to the end of the key's current value. For
instance, `["append", 5, 3]` means "add 3 to the end of the list for key
5." If key 5 were currently `[1, 2]`, the resulting value would become `[1,
2, 3]`. Appends have values provided by the client, and are returned
unchanged.

Unlike lin-kv, nonexistent keys should be returned as `null`. Lists are
implicitly created on first append.

This workload can check many kinds of consistency models. See the
`--consistency-models` CLI option for details. 

### RPC: Txn! 

Requests that the node execute a single transaction. Servers respond with a
`txn_ok` message, and a completed version of the requested transaction; e.g.
with read values filled in. Keys and list elements may be of any type. 

Request:

```clj
{:type (eq "txn"),
 :txn
 [(either
   [(one (eq "r") "f") (one Any "k") (one (eq nil) "v")]
   [(one (eq "append") "f") (one Any "k") (one Any "v")])],
 :msg_id Int}
```

Response:

```clj
{:type (eq "txn_ok"),
 :txn
 [(either
   [(one (eq "r") "f") (one Any "k") (one [Any] "v")]
   [(one (eq "append") "f") (one Any "k") (one Any "v")])],
 #schema.core.OptionalKey{:k :msg_id} Int,
 :in_reply_to Int}
```



