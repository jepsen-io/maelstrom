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

## Documentation

Start by skimming the [Protocol](doc/protocol.md) docs, which define
Maelstrom's "network" protocol, message structure, and error handling.

The [Workload](doc/workload) docs describe the various kinds of workloads that
Maelstrom can test, and define the messages involved in that particular
workload.

The Maelstrom Guide will take you through writing several different types of
distributed algorithms using Maelstrom. We begins by setting up Maelstrom and
its dependencies, write our own tiny echo server, and move on to more
sophisticated workloads.

- [Chapter 1: Getting Ready](doc/01-getting-ready/index.md)
- [Chapter 2: Echo](doc/02-echo/index.md)
- [Chapter 5: Raft](doc/05-raft/index.md)

## CLI Options

A full list of options is available by running `java -jar maelstrom.jar test
--help`. The important ones are:

- `--workload NAME`: What kind of workload should be run?
- `--bin SOME_BINARY`: The program you'd like Maelstrom to spawn instances of
- `--node-count NODE-NAME`: How many instances of the binary should be spawned?

To get more information, use:

- `--log-stderr`: Show STDERR output from each node in the Maelstrom log
- `--log-net-send`: Log messages as they are sent into the network
- `--log-net-recv`: Log messages as they are received by nodes

To make tests more or less aggressive, use:

- `--nemesis FAULT_TYPE`: A comma-separated list of faults to inject
- `--nemesis-interval SECONDS`: How long between nemesis operations, on average
- `--concurrency INT`: Number of clients to run concurrently
- `--rate FLOAT`: Approximate number of requests per second
- `--time-limit SECONDS`: How long to run tests for
- `--latency MILLIS`: Approximate simulated network latency, during normal
  operations.

SSH options are unused; Maelstrom runs entirely on the local node.

## Test Results

Test results are stored in `store/<test-name>/<timestamp>/`. See `store/latest`
for a symlink to the most recently completed test, and `store/current` for a
symlink to the currently executing (or most recently completed) test.

You can view these files at the command line, using a file explorer, or via
Maelstrom's built-in web server. Run `java -jar maelstrom.jar serve` (or `lein
run serve`) to launch the server on port 8080.

Each workload generates slightly different files and output, but in every test,
you'll find:

- `jepsen.log`: The full logs from the test run, as printed to the console.

- `results.edn`: The results of the test's checker, including statistics and
  safety analysis. This structure is also printed at the end of a test.

- `history.edn`: The history of all operations performed during the test, including reads, writes, and nemesis operations.

- `history.txt`: A condensed, human-readable representation of the history.
  Columns are `process`, `type`, `f`, `value`, and `error`.

- `messages.svg`: A spacetime diagram of messages exchanged. Time flows
  (nonlinearly) from top to bottom, and each node is shown as a vertical bar.
  Messages are diagonal arrows between nodes, colorized by type. Hovering over
  a message shows the message itself. Helpful for understanding how your
  system's messages are flowing between nodes.

- `log/n*.log`: The STDERR logs emitted by each node.

- `timeline.html`: A diagram where time flows (nonlinearly) down, and each
  process's operations are arranged in a vertical track. Blue indicates `ok`
  operations, orange indicates indefinite (`info`) operations, and pink
  indicates `fail`ed operations. Helpful in understanding the concurrency
  structure of the test, as visible to Maelstrom.

- `test.fressian`: A machine-readable copy of the entire test, including
  history and analysis.

`results.edn` is a map with a single keyword, `:valid?`, which can be one of
three values:

- `true`: The checker considers this history legal.
- `:unknown`: The checker could not determine whether the history was valid.
  This could happen if, for instance, the history contains no reads.
- `false`: The checker identified an error of some kind.

These values determine the colors of test directories in the web interface:
blue means `:valid? true`, orange means `:valid? :unknown`, and pink means
`:valid? false`. They also determine Jepsen's exit status when running a test:
`true` exits with status 0, 1 indicates a failure, and 2 indicates an :unknown.
Other exit statuses indicate internal Jepsen errors, like a crash.


## License

Copyright © 2017, 2020 Kyle Kingsbury & Jepsen, LLC

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
