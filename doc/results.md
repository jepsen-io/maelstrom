# Understanding Test Results

Test results are stored in `store/<test-name>/<timestamp>/`. See `store/latest`
for a symlink to the most recently completed test, and `store/current` for a
symlink to the currently executing (or most recently completed) test.

You can view these files at the command line, using a file explorer, or via
Maelstrom's built-in web server. Run `java -jar maelstrom.jar serve` (or `lein
run serve`) to launch the server on port 8080.

## Common Files

Each [workload](workloads.md) generates slightly different files and output,
but in every test, you'll find:

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

- `timeline.html`: A diagram where time flows (nonlinearly) down, and each
  process's operations are arranged in a vertical track. Blue indicates `ok`
  operations, orange indicates indefinite (`info`) operations, and pink
  indicates `fail`ed operations. Helpful in understanding the concurrency
  structure of the test, as visible to Maelstrom.

- `latency-raw.png`: Shows the latency of each Maelstrom operation, plotted
  over time by the time the request began. Color indicates whether the
  operation completed with `ok`, `info`, or `fail`. Shape indicates the `f`
  function for that operation: e.g. a `read`, `write`, `cas`, etc.

- `latency-quantiles.png`: The same latency timeseries, binned by time,
  intervals and projected to quantiles 0.5, 0.95, 0.99, and 1.

- `rate.png`: The overall rate of requests per second, over time, broken down
  by `:f` and `:type`.

- `log/n*.log`: The STDERR logs emitted by each node.

- `test.fressian`: A machine-readable copy of the entire test, including
  history and analysis.

## Interpreting Results

At the end of each test, Maelstrom analyzes the history of operations using a
*checker*, which produces *results*. Those results are printed to the console
at the end of the test, and also written to `results.edn`. At a high level,
results are a map with a single mandatory keyword, `:valid?`, which can be one
of three values:

- `true`: The checker considers this history legal.
- `:unknown`: The checker could not determine whether the history was valid.
  This could happen if, for instance, the history contains no reads.
- `false`: The checker identified an error of some kind.

These values determine the colors of test directories in the web interface:
blue means `:valid? true`, orange means `:valid? :unknown`, and pink means
`:valid? false`. They also determine Jepsen's exit status when running a test:
`true` exits with status 0, 1 indicates a failure, and 2 indicates an :unknown.
Other exit statuses indicate internal Jepsen errors, like a crash.

Maelstrom's results are a combination of several different checkers:

- `perf` is always valid, and generates performance graphs like
  `latency-raw.png`.

- `timeline` is always valid, and generates `timeline.html`

- `exceptions` collects unhandled exceptions thrown during the course of a test--for example, if your binary generates malformed RPC responses.

- `stats` collects basic statistics: the overall `:count` of operations, how
  many were `ok`, `failed`, or crashed with `info`, as well as breakdowns for
  each function (`by-f`), so you can see specifically how many reads vs writes
  failed.

- `net` shows network statistics, including the overall number of sent,
  received, and total unique messages, and breakdowns for traffic between
  clients and servers vs between servers.

- `workload` depends on the checker for that particular workload. See the
  [workload documentation](workloads.md) for details.
