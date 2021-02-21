# Getting Ready

This guide makes sure that you're ready to work with Maelstrom. We'll install
Maelstrom's prerequisites and run a basic test of a pre-existing demonstration
server.

## Prerequisites

### JDK

To run Maelstrom, you'll need a JDK (Java Development Kit), version 1.8 or
higher. If you don't have a JDK yet, you can download one from
[Oracle](http://www.oracle.com/technetwork/java/javase/downloads/index.html),
or use your system's package manager: e.g. `apt install openjdk-17-jdk`.

```
$ java -version
openjdk 14.0.2 2020-07-14
OpenJDK Runtime Environment (build 14.0.2+12-Debian-2)
OpenJDK 64-Bit Server VM (build 14.0.2+12-Debian-2, mixed mode, sharing)
```

### Leiningen

Leiningen is a build system for the Clojure language, and is how we'll run
Maelstrom. To install it, see the [Lein installation docs](https://leiningen.org/#install). Once installed, you should be able to run:

```
$ lein --version
Leiningen 2.9.4 on Java 14.0.2 OpenJDK 64-Bit Server VM
```

### Graphviz

Maelstrom uses [Graphviz](https://graphviz.org/) to generate plots of
consistency anomalies. This isn't required for all workloads, but you'll want
it for more sophisticated tests. You can install Graphviz from the [project
site](https://graphviz.org/download/) or via your package manager: e.g. `apt
install graphviz`. Once installed, you should be able to run:

```
$ dot -V
dot - graphviz version 2.43.0 (0)
```

### Gnuplot

This isn't required, but if you have Gnuplot installed, Maelstrom can generate
plots to help you understand test behavior. You can install Gnuplot from [the
official Gnuplot site](http://www.gnuplot.info/download.html) or via your
package manager: e.g. `apt install gnuplot`. Once installed, you should be able to run:

```
$ gnuplot --version
gnuplot 5.4 patchlevel 1
```

## Run a Demo

To check that everything's ready to go, you can run Maelstrom against a demonstration program. The program that we'll run is in `demo/echo/echo.rb`. Since this program is written in Ruby, you'll need a [Ruby interpreter](https://www.ruby-lang.org/en/documentation/installation/) installed.

```
$ ruby --version
ruby 2.7.2p137 (2020-10-01 revision 5445e04352) [x86_64-linux-gnu]
```

Let's make sure we can run one instance of the echo server. In the top-level `maelstrom/` directory, run:

```
$ demo/echo/echo.rb
Online
```

The program should pause, awaiting input. If you like, you can type or paste a JSON message into the console, like so:

```
{"src": "c1", "dest": "n1", "body": {"msg_id": 1, "type": "echo", "echo": "hello there"}}
```

This should print

```
Received {:src=>"c1", :dest=>"n1", :body=>{:msg_id=>1, :type=>"echo", :echo=>"hello there"}}
Sent {:dest=>"c1", :src=>nil, :body=>{:msg_id=>1, :type=>"echo", :echo=>"hello there", :in_reply_to=>1}}
{"dest":"c1","src":null,"body":{"msg_id":1,"type":"echo","echo":"hello there","in_reply_to":1}}
```

The `Received` and `Sent` lines are informational logging, sent by the server
to stderr. The `{"dest": ...}` JSON message is the server's response to our
request, printed on stdout. This is how Maelstrom will interact with our server.

Type `[Control] + [c]` to exit the server.

Now, let's run Maelstrom, and ask it to test the server:

```
lein run test -w echo --bin demo/echo/echo.rb --time-limit 5
```

This starts a Maelstrom test with the `echo` workload. Maelstrom will generate
requests just like the JSON message we pasted into the server, and read
responses back from each server. The `--bin` argument tells it which program to run for the server. `--time-limit 5` means that we'd like to test for only five seconds. This should print something like:

```
INFO [2021-02-07 16:03:31,972] jepsen test runner - jepsen.core {:perf {:latency-graph {:valid? true},
        :rate-graph {:valid? true},
        :valid? true},
 :exceptions {:valid? true},
 :stats {:valid? true,
         :count 5,
         :ok-count 5,
         :fail-count 0,
         :info-count 0,
         :by-f {:echo {:valid? true,
                       :count 5,
                       :ok-count 5,
                       :fail-count 0,
                       :info-count 0}}},
 :workload {:valid? true, :errors ()},
 :valid? true}


Everything looks good! ヽ(‘ー`)ノ
```

Maelstrom starts five copies of `echo.rb`, connects them via its simulated
network, and constructs five clients to perform echo requests against those
servers. Finally, it checks to make sure that for every request, that server
sent back a corresponding echo response.

The results of this test are written to a directory in
`store/<test-name>/<timestamp>/`; `store/latest` is a symlink to the most
recently completed test. In that directory, you'll find:

- 'n1.log`, `n2.log`, etc: The STDERR logs from each node
- jepsen.log: The full log from the Maelstrom run
- history.txt: The logical history of the system, as seen by clients
- results.edn: Analysis results
- latency-raw.png: Raw operation latencies during the test. Grey regions
  indicate network partitions.

You can browse these files using a file explorer, at the console, or via a web server that comes built-in to Maelstrom. In the main `maelstrom` directory, run:

```sh
lein run serve
22:41:00.605 [main] INFO  jepsen.web - Web server running.
22:41:00.608 [main] INFO  jepsen.cli - Listening on http://0.0.0.0:8080/
```

Congratulations! You're ready to start writing your own systems!
