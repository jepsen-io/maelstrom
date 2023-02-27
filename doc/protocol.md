# Protocol

Maelstrom nodes receive messages on STDIN, send messages on STDOUT, and log
debugging output on STDERR. Maelstrom nodes must not print anything that is not
a message to STDOUT. Maelstrom will log STDERR output to disk for you.

## Nodes and Networks

A Maelstrom test simulates a distributed system by running many *nodes*, and a
network which routes *messages* between them. Each node has a unique string
identifier, used to route messages to and from that node.

- Nodes `n1`, `n2`, `n3`, etc. are instances of the binary you pass to
  Maelstrom. These nodes implement whatever distributed algorithm you're trying
  to build: for instance, a key-value store. You can think of these as
  *servers*, in that they accept requests from clients and send back responses.

- Nodes `c1`, `c2`, `c3`, etc. are Maelstrom's internal clients. Clients send
  requests to servers and expect responses back, via a simple asynchronous [RPC
  protocol](#message-bodies).

## Messages

Both STDIN and STDOUT messages are JSON objects, separated by newlines (`\n`). Each message object is of the form:

```edn
{
  "src":  A string identifying the node this message came from
  "dest": A string identifying the node this message is to
  "body": An object: the payload of the message
}
```

## Message Bodies

RPC messages exchanged with Maelstrom's clients have bodies with the following
reserved keys:

```edn
{
  "type":        (mandatory) A string identifying the type of message this is
  "msg_id":      (optional)  A unique integer identifier
  "in_reply_to": (optional)  For req/response, the msg_id of the request
}
```

Message IDs should be unique on the node which sent them. For instance, each
node can use a monotonically increasing integer as their source of message IDs.

Each message has additional keys, depending on what kind of message it is. For
example, here is a read request from the `lin_kv` workload, which asks for the
current value of key `3`:

```json
{
  "type": "read",
  "msg_id": 123,
  "key": 3
}
```

And its corresponding response, indicating the value is presently `4`:

```json
{
  "type": "read_ok",
  "msg_id": 56,
  "in_reply_to": 123,
  "value": 4
}
```

The various message types and the meanings of their fields are defined in the
[workload documentation](workloads.md).

Messages exchanged between your server nodes may have any `body` structure you
like; you are not limited to request-response, and may invent any message
semantics you choose. If some of your messages *do* use the body format
described above, Maelstrom can help generate useful visualizations and
statistics for those messages.

## Initialization

At the start of a test, Maelstrom issues a single `init` message to each node,
like so:

```json
{
  "type":     "init",
  "msg_id":   1,
  "node_id":  "n3",
  "node_ids": ["n1", "n2", "n3"]
}
```

The `node_id` field indicates the ID of the node which is receiving this
message: here, the node ID is "n1". Your node should remember this ID and
include it as the `src` of any message it sends.

The `node_ids` field lists all nodes in the cluster, including the recipient.
All nodes receive an identical list; you may use its order if you like.

In response to the `init` message, each node must respond with a message of
type `init_ok`.

```json
{
  "type":        "init_ok",
  "in_reply_to": 1
}
```

## Errors

In response to a Maelstrom RPC request, a node may respond with an *error*
message, whose `body` is a JSON object like so:

```json
{"type":        "error",
 "in_reply_to": 5,
 "code":        11,
 "text":        "Node n5 is waiting for quorum and cannot service requests yet"
}
```

The `type` of an error body is always `\"error\"`.

As with all RPC responses, the `in_reply_to` field is the `msg_id` of
the request which caused this error.

The `code` is an integer which indicates the type of error which occurred.
Maelstrom defines several error types, and you can also invent your own.
Codes 0-999 are reserved for Maelstrom's use; codes 1000 and above are free
for your own purposes.

The `text` field is a free-form string. It is optional, and may contain any
explanatory message you like.

You may include other keys in the error body, if you like; Maelstrom will
retain them as a part of the history, and they may be helpful in your own
analysis.

Errors are either *definite* or *indefinite*. A definite error means that the
requested operation definitely did not (and never will) happen. An indefinite
error means that the operation might have happened, or might never happen, or
might happen at some later time. Maelstrom uses this information to interpret
histories correctly, so it's important that you never return a definite error
under indefinite conditions. When in doubt, indefinite is always safe. Custom
error codes are always indefinite.

The following table lists all of Maelstrom's defined errors.


| Code | Name | Definite | Description |
| ---: | :--- | :------: | :---------- |
| 0 | timeout |   | Indicates that the requested operation could not be completed within a timeout. |
| 1 | node-not-found | ✓ | Thrown when a client sends an RPC request to a node which does not exist. |
| 10 | not-supported | ✓ | Use this error to indicate that a requested operation is not supported by the current implementation. Helpful for stubbing out APIs during development. |
| 11 | temporarily-unavailable | ✓ | Indicates that the operation definitely cannot be performed at this time--perhaps because the server is in a read-only state, has not yet been initialized, believes its peers to be down, and so on. Do *not* use this error for indeterminate cases, when the operation may actually have taken place. |
| 12 | malformed-request | ✓ | The client's request did not conform to the server's expectations, and could not possibly have been processed. |
| 13 | crash |   | Indicates that some kind of general, indefinite error occurred. Use this as a catch-all for errors you can't otherwise categorize, or as a starting point for your error handler: it's safe to return `internal-error` for every problem by default, then add special cases for more specific errors later. |
| 14 | abort | ✓ | Indicates that some kind of general, definite error occurred. Use this as a catch-all for errors you can't otherwise categorize, when you specifically know that the requested operation has not taken place. For instance, you might encounter an indefinite failure during the prepare phase of a transaction: since you haven't started the commit process yet, the transaction can't have taken place. It's therefore safe to return a definite `abort` to the client. |
| 20 | key-does-not-exist | ✓ | The client requested an operation on a key which does not exist (assuming the operation should not automatically create missing keys). |
| 21 | key-already-exists | ✓ | The client requested the creation of a key which already exists, and the server will not overwrite it. |
| 22 | precondition-failed | ✓ | The requested operation expected some conditions to hold, and those conditions were not met. For instance, a compare-and-set operation might assert that the value of a key is currently 5; if the value is 3, the server would return `precondition-failed`. |
| 30 | txn-conflict | ✓ | The requested transaction has been aborted because of a conflict with another transaction. Servers need not return this error on every conflict: they may choose to retry automatically instead. |
