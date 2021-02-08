# Maelstrom

Maelstrom is a workbench for learning distributed systems by writing your own,
tiny implementations of distributed algorithms. It's used as a part of a
distributed systems workshop by [Jepsen](https://jepsen.io/training).

## Overview

Maelstrom is a [Clojure](https://clojure.org/) program which runs on the [Java
Virtual Machine](https://en.wikipedia.org/wiki/Java_virtual_machine). It uses
the [Jepsen](https://github.com/jepsen-io/jepsen) testing library to test toy
implementations of distributed systems. Maelstrom provides standardized tests
for things like "a commutative set" or "a linearizable key-value store", and
lets you learn by writing implementations which those test suites can
exercise.

Writing "real" distributed systems involves a lot of busywork: process
management, networking, and message serialization are complex, full of edge
cases, and difficult to debug across languages. In addition, running a full
cluster of virtual machines connected by a real IP network is tricky for many
users. Maelstrom strips these problems away so you can focus on the algorithmic
essentials: process state, transitions, and messages.

The "nodes" in a Maelstrom test are simply programs, written in any language.
Nodes read "network" messages as JSON from STDIN, write JSON "network" messages
to STDOUT, and do their logging to STDERR. Maelstrom runs those nodes as
processes on your local machine, and connects them via a simulated network.
Maelstrom runs a collection of simulated network clients which make requests to
those nodes, receive responses, and records a history of those operations. At
the end of a test run, Maelstrom analyzes that history to identify safety
violations.

This allows learners to write their nodes in whatever language they are most
comfortable with, without having to worry about discovery, network
communication, daemonization, writing their own distributed test harness, and
so on. It also means that Maelstrom can perform sophisticated fault injection:
dropping, delaying, duplicating, and reordering network messages.

## Guide

This guide will take you through writing several different types of distributed
algorithms using Maelstrom. We'll begin by setting up Maelstrom and its
dependencies, write our own tiny echo server, and move on to more sophisticated
workloads.

- [Chapter 1: Getting Ready](doc/01-getting-ready/index.md)
- [Chapter 2: Echo](doc/02-echo/index.md)
- [Chapter 5: Raft](doc/05-raft/index.md)


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
```

Codes 1, 10, and 11 are understood to be *definite* failures: they
indicate the request cannot have, and will never, succeed. Other error codes
are *indeterminate*: Maelstrom assumes they may or may not succeed.

Use code 10 when developing your server, as a stub to indicate failure. Use
code 11 for things like requests made to an uninitialized node, or a node which
is a follower.

## License

Copyright © 2017, 2020 Kyle Kingsbury, Jepsen, LLC

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
