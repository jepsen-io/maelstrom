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
- [Kafka](#workload-kafka)
- [Lin-kv](#workload-lin-kv)
- [Pn-counter](#workload-pn-counter)
- [Txn-list-append](#workload-txn-list-append)
- [Txn-rw-register](#workload-txn-rw-register)
- [Unique-ids](#workload-unique-ids)

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



## Workload: Kafka 

A simplified version of a Kafka-style stream processing system. Servers
provide a set of append-only logs identified by string *keys*. Each integer
*offset* in a log has one *message*. Offsets may be sparse: not every offset
must contain a message.

A client appends a message to a log by making a `send` RPC request with the
key and value they want to append; the server responds with the offset it
assigned for that particular message.

To read a log, a client issues a `poll` RPC request with a map of keys to the
offsets it wishes to read beginning at. The server returns a `poll_ok`
response containing a map of keys to vectors of `[offset, message]` pairs,
beginning at the requested offset for that key.

Servers should maintain a *committed offset* for each key. Clients can
request that this offset be advanced by making a `commit_offsets` RPC
request, with a map of keys to the highest offsets which that client has
processed. Clients can also fetch known-committed offsets for a given set of
keys through a `fetch_committed_offsets` request.

The checker for this workload detects a number of anomalies.

If a client observes (e.g.) offset 10 of key `k1`, but *not* offset 5, and we
know that offset 5 exists, we call that a *lost write*. If we know offset 11
exists, but it is never observed in a poll, we call that write *unobserved*.
There is no recency requirement: servers are free to acknowledge a sent
message, but not return it in any polls for an arbitrarily long time.

Ideally, we expect client offsets from both sends and polls to be strictly
monotonically increasing, and to observe every message. If offsets go
backwards--e.g. if a client observes offset 4 then 2, or 2 then 2--we call
that *nonomonotonic*. It's an *internal nonmonotonic* error if offsets fail
to increase in the course of a single transaction, or single poll. It's an
*external nonmonotonic* error if offsets fail to increase *between* two
transactions.

If we skip over an offset that we know exists, we call that a *skip*. Like
nonmonotonic errors, a skip error can be internal (in a single transaction or
poll) or external (between two transactions). For example, here is a
poll-skip anomaly:

```edn
{:key "56",
:delta 3,
:skipped (7 8),
:ops [#jepsen.history.Op{:index 3820,
:time 4650651388,
:type :ok,
:process 0,
:f :poll,
:value [[:poll {"56" [[4 5] [5 6]]}]]}
#jepsen.history.Op{:index 3848,
:time 4691738701,
:type :ok,
:process 0,
:f :poll,
:value [[:poll {"56" [[8 9]]}]]}]}
```

Here a single client (process 0) performed two polls in succession, both of
which observed key "56". The first poll observed offsets 4 and 5 (with
messages 5 and 6). The second poll observed offset 8, with message 9. The
client unexpectedly jumped three offsets ahead, skipping messages 7 and 8.

The Jepsen client performs `:assign` operations, which is analogous to the
Kafka client's `assign`: it picks a new set of keys and offsets for
successive `poll` operations. On assign, the client fetches committed offsets
from the server and begins polling from those positions. Since we expect the
offset to change on assign, external nonmonotonic and skip errors are not
tracked across `assign` operations. 

### RPC: Send! 

Sends a single message to a specific key. The server should assign a unique
offset in the key for this message, and return a `send_ok` response with that
offest. 

Request:

```clj
{:type (eq "send"),
 :key (named Str "key"),
 :msg (named Any "msg"),
 :msg_id Int}
```

Response:

```clj
{:type (eq "send_ok"),
 :offset (named Int "offset"),
 #schema.core.OptionalKey{:k :msg_id} Int,
 :in_reply_to Int}
```


### RPC: Poll 

Requests messages from specific keys. The client provides a map `offsets`,
whose keys are the keys of distinct queues, and whose values are the
corresponding offsets the server should send back first (if available). For
instance, a client might request

```edn
{"type": "poll",
"offsets": {"a": 2}}
```

This means that the client would like to see messages from key "a"
beginning with offset 2. The server is free to respond with any number of
contiguous messages from queue "a" so long as the first message's offset is
2. Those messages are returned as a map of keys to arrays of [offset message]
pairs. For example:

```edn
{"type": "poll_ok",
"msgs": {"a": [[2 9] [3 5] [4 15]]}}
```

In queue "a", offset 2 has message 9, offset 3 has message 5, and offset 4
has message 15. If no messages are available for a key, the server can omit
that key from the response map altogether. 

Request:

```clj
{:type (eq "poll"),
 :offsets {(named Str "key") (named Int "offset")},
 :msg_id Int}
```

Response:

```clj
{:type (eq "poll_ok"),
 :msgs
 {(named Str "key")
  [[#schema.core.One{:schema (named Int "offset"),
                     :optional? false,
                     :name "offset"}
    #schema.core.One{:schema (named Any "msg"),
                     :optional? false,
                     :name "msg"}]]},
 #schema.core.OptionalKey{:k :msg_id} Int,
 :in_reply_to Int}
```


### RPC: Commit_offsets! 

Informs the server that the client has successfully processed messages up to
and including the given offset. For instance, if a client sends:

```edn
{:type    "commit_offsets"
:offsets {"k1" 2}}
```

This means that on key `k1` offsets 0, 1, and 2 (if they exist; offsets may
be sparse) have been processed, but offsets 3 and higher have not. To avoid
lost writes, the servers should ensure that any fresh assign of key `k1`
starts at offset 3 (or lower). 

Request:

```clj
{:type (eq "commit_offsets"),
 :offsets {(named Str "key") (named Int "offset")},
 :msg_id Int}
```

Response:

```clj
{:type (eq "commit_offsets_ok"),
 #schema.core.OptionalKey{:k :msg_id} Int,
 :in_reply_to Int}
```


### RPC: List_committed_offsets 

Requests the latest committed offsets for the given array of keys. The
server should respond with a map of keys to offsets. If a key does not exist,
it can be omitted from the response map. Clients use this to figure out where
to start consuming from a given key. 

Request:

```clj
{:type (eq "list_committed_offsets"),
 :keys [(named Str "key")],
 :msg_id Int}
```

Response:

```clj
{:type (eq "list_committed_offsets_ok"),
 :offsets {(named Str "key") (named Int "offset")},
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
submit a single transaction via a `txn` request, and expect a
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

For example, assume the current state of the database is `{1 [8]}`, and you
receive a request body like:

```json
{"type": "txn",
"msg_id": 1,
"txn": [["r", 1, null], ["append", 1, 6], ["append", 2, 9]]}
```

You might return a response like:

```json
{"type": "txn_ok",
"in_reply_to": 1,
"txn": [["r", 1, [8]], ["append", 1, 6], ["append", 2, 9]]}
```

First you read the current value of key 1, returning the list [8]. Then you
append 6 to key 1. Then you append 9 to key 2, implicitly creating it. The
resulting state of the database would be `{1 [8, 6], 2 [9]}`.

Appends in this workload are always integers, and are unique per key. Key
`x` will only ever see at most one append of `0`, at most one append of `1`,
and so on.

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



## Workload: Txn-rw-register 

A transactional workload over a map of keys to values. Clients
submit a single transaction via a `txn` request, and expect a
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

A *write* replaces the value for a key. For instance, `["w", 5, 3]` means
"set key 5's value to 3". Write values are provided by the client, and are
returned unchanged.

For example, assume the current state of the database is `{1 8}`, and you
receive a request body like:

```json
{"type": "txn",
"msg_id": 1,
"txn": [["r", 1, null], ["w", 1, 6], ["w", 2, 9]]}
```

You might return a response like:

```json
{"type": "txn_ok",
"in_reply_to": 1,
"txn": [["r", 1, 8], ["w", 1, 6], ["w", 2, 9]]}
```

First you read the current value of key 1, returning the value 8. Then you
set key 1 to 6. Then you set key 2 to 9, implicitly creating it. The
resulting state of the database would be `{1 6, 2 9}`.

Writes in this workload are always integers, and are unique per key. Key
`x` will only ever see at most one write of `0`, at most one write of `1`,
and so on.

Unlike lin-kv, nonexistent keys should be returned as `null`. Keys are
implicitly created on first write.

This workload can check many kinds of consistency models. See the
`--consistency-models` CLI option for details. Right now it only uses
writes-follow-reads within individual transactions to infer version
orders--this severely limits the transaction dependencies it can infer. We
can make this configurable later. 

### RPC: Txn! 

Requests that the node execute a single transaction. Servers respond with a
`txn_ok` message, and a completed version of the requested transaction--e.g.
with read values filled in. Keys and values may be of any type, but if you
need types for your language, it's probably safe to assume both are
integers. 

Request:

```clj
{:type (eq "txn"),
 :txn
 [(either
   [(one (eq "r") "f") (one Any "k") (one (eq nil) "v")]
   [(one (eq "w") "f") (one Any "k") (one Any "v")])],
 :msg_id Int}
```

Response:

```clj
{:type (eq "txn_ok"),
 :txn
 [(either
   [(one (eq "r") "f") (one Any "k") (one Any "v")]
   [(one (eq "w") "f") (one Any "k") (one Any "v")])],
 #schema.core.OptionalKey{:k :msg_id} Int,
 :in_reply_to Int}
```



## Workload: Unique-ids 

A simple workload for ID generation systems. Clients ask servers to generate
an ID, and the server should respond with an ID. The test verifies that those
IDs are globally unique.

Your node will receive a request body like:

```json
{"type": "generate",
"msg_id": 2}
```

And should respond with something like:

```json
{"type": "generate_ok",
"in_reply_to": 2,
"id": 123}
```

IDs may be of any type--strings, booleans, integers, floats, compound JSON
values, etc. 

### RPC: Generate! 

Asks a node to generate a new ID. Servers respond with a generate_ok message
containing an `id` field, which should be a globally unique value. IDs may be
of any type. 

Request:

```clj
{:type (eq "generate"), :msg_id Int}
```

Response:

```clj
{:type (eq "generate_ok"),
 :id Any,
 #schema.core.OptionalKey{:k :msg_id} Int,
 :in_reply_to Int}
```



