# Bootstrapping Maelstrom

In this guide, we'll write an implementation of the [Raft consensus
algorithm](https://raft.github.io/raft.pdf) in Python, using the Maelstrom
testing library.

To get set up, we'll need a Python interpreter, a recent JVM, and the Leiningen
build system for Clojure. Your package manager probably has Python packages--if not, [Python.org](https://www.python.org/downloads/) has pages. This guide is based on 2.7, but 3.x should work with relatively few changes.

```
$ python --version
Python 2.7.13
```

You can download Java from [Oracle](http://www.oracle.com/technetwork/java/javase/downloads/index.html), or use your system's package manager.

```
$ java -version
java version "1.8.0_40"
Java(TM) SE Runtime Environment (build 1.8.0_40-b25)
Java HotSpot(TM) 64-Bit Server VM (build 25.40-b25, mixed mode)
```

Leiningen is the Clojure build system, and it's how we'll run Maelstrom. To install it, see [the Lein install docs](https://leiningen.org/#install).

```
$ lein --version
Leiningen 2.8.1 on Java 1.8.0_40 Java HotSpot(TM) 64-Bit Server VM
```

This isn't required, but to get nice graphs of each test run, you'll want gnuplot installed and on your `$PATH` as `gnuploy` as well.

```
$ gnuplot --version
gnuplot 5.0 patchlevel 5
```

With these preliminaries out of the way, let's start coding!

## A basic server

Maelstrom works with any kind of binary, feeding it network communication over
stdin, receiving network messages over stdout, and logging information on
stderr. The binary we're going to write is a python script, so open up a new
file in your favorite editor--let's call it `raft.py`:

```py
#!/usr/bin/env python

import sys

sys.stderr.write('hello, world\n')
```

We'll have to make that script executable, so we can run it:

```
$ chmod +x raft.py
$ ./raft.py
hello, world
```

Cool, there's a tiny server. Note that we're printing to stderr--that's the way
Maelstrom programs produce debugging information.

Let's try this server out with Maelstrom. We'll tell Maelstrom our binary is
`raft.py`, that it should run one instance of that server on a node called
`n1`, that we'd like to run the test for ten seconds, that the rate of requests
should be 0 per second, and that we'd like to see stderr log messages in the
Maelstrom logs.

```
$ lein run test --bin raft.py --nodes n1 --time-limit 10 --rate 0 --log-stderr
...
INFO [2018-06-06 17:04:12,938] jepsen test runner - jepsen.db Tearing down DB
INFO [2018-06-06 17:04:12,941] jepsen test runner - jepsen.db Setting up DB
INFO [2018-06-06 17:04:12,942] jepsen node n1 - maelstrom.core Setting up n1
INFO [2018-06-06 17:04:12,944] jepsen node n1 - maelstrom.process launching raft.py []
INFO [2018-06-06 17:04:13,041] node n1 - maelstrom.process hello, world
INFO [2018-06-06 17:04:23,024] jepsen node n1 - maelstrom.core Tearing down n1
ERROR [2018-06-06 17:04:23,053] main - jepsen.cli Oh jeez, I'm sorry, Jepsen broke. Here's why:
java.util.concurrent.ExecutionException: java.lang.RuntimeException: timed out
```

Maelstrom started our node `n1`, calling `raft.py` with no arguments. It got
the log message `hello, world`, then timed out. That's just fine. Maelstrom expected our server to do more, but this is a good first start!

## A Tiny Network Server

Maelstrom sent our server an initial network message, telling that node about
what its ID was, who else is in the cluster, and so on. We're expected to
receive, parse, and acknowledge that message. To do that, we'll need server
that can read messages from stdin.

```py
#!/usr/bin/env python

import sys

class Net():
    """Handles console IO for sending and receiving messages."""
    
    def __init__(self):
        """Constructs a new network client."""
        pass
    
    def process_msg(self):
        """Handles a message from stdin, if one is currently available."""
        line = sys.stdin.readline()
        if not line:
            return None
        
        sys.stderr.write('Got line: ' + line + '\n')
        
Net().process_msg()
```

We'll use the Net class to decouple wire handling, IO, and parsing from the
logical structure of sending and receiving messages. Right now there's no
state, but we're going to need some later, so it's a class, rather than a
function. Our `process_msg` function just parses a line, makes sure it exists,
and logs it to the console. Let's give that a shot!

```
$ lein run test --bin raft.py --nodes n1 --rate 0 --time-limit 10 --log-stderr
INFO [2018-06-06 17:30:07,361] node n1 - maelstrom.process Got line: {"dest":"n1","body":{"type":"raft_init","node_id":"n1","node_ids":["n1"],"msg_id":1},"src":"c1"}
```

Hey, we got a message! It's a JSON structure, from Maelstrom. Let's bring in a
JSON parser to decode it. Since JSON is UTF-8, and we don't want to be
constantly converting between ASCII and Unicode strings, we'll also flip
everything in our program to unicode literals.

```py
from __future__ import unicode_literals
import json
from pprint import pformat
```

And in `process_msg`...

```py
    def process_msg(self):
        """Handles a message from stdin, if one is currently available."""
        line = sys.stdin.readline()
        if not line:
            return None
        
        msg = json.loads(line)
        sys.stderr.write('Received\n' + pformat(msg) + '\n')
```

```
$ lein run test --bin raft.py --nodes n1 --rate 0 --time-limit 10 --log-stderr
INFO [2018-06-06 17:48:53,636] node n1 - maelstrom.process Received
INFO [2018-06-06 17:48:53,637] node n1 - maelstrom.process {u'body': {u'msg_id': 1,
INFO [2018-06-06 17:48:53,637] node n1 - maelstrom.process            u'node_id': u'n1',
INFO [2018-06-06 17:48:53,638] node n1 - maelstrom.process            u'node_ids': [u'n1'],
INFO [2018-06-06 17:48:53,638] node n1 - maelstrom.process            u'type': u'raft_init'},
INFO [2018-06-06 17:48:53,638] node n1 - maelstrom.process  u'dest': u'n1',
INFO [2018-06-06 17:48:53,638] node n1 - maelstrom.process  u'src': u'c1'}
```

Fishing this out of the Maelstrom logs can be a little time-consuming, so
Maelstrom also writes individual node log files to `store/latest`. Let's take a
look there.

```
$ cat store/latest/n1.log 
Received
{u'body': {u'msg_id': 1,
           u'node_id': u'n1',
           u'node_ids': [u'n1'],
           u'type': u'raft_init'},
 u'dest': u'n1',
 u'src': u'c1'}
```

That's better! This is the initialization message that Maelstrom provides to
each node in the cluster. The envelope includes the node which sent the message
(`src`), the node which it was destined for (`dest`), and a payload, (`body`).
That body tells us that this node's ID is `n1`, and that the total set of nodes
in the cluster is `[n1]`. We'll use this message to initialize our Raft state.

## Node Initialization

We'll need a place to store our node ID, cluster information, and so on. Let's
call that a RaftNode.

```py
class RaftNode():
    def __init__(self):
        self.node_id = None     # Our node ID
        self.node_ids = None    # The set of node IDs

        self.state = 'nascent'  # One of nascent, follower, candidate, or leader

        self.net = Net()
```

Now we need to have our node receive its initialization message and set itself up. Let's write a main function for RaftNode:

```py
    def main(self):
        """Entry point"""
        sys.stderr.write('Online.\n')

        # Handle initialization message
        msg = self.net.process_msg()
        body = msg['body']
        if body['type'] != 'raft_init':
            raise RuntimeError("Unexpected message " + pformat(msg))

        self.node_id = body['node_id']
        self.node_ids = body['node_ids']
        sys.stderr.write('I am: ' + str(self.node_id))```
```

We'll modify `process_msg` to *return*, rather than print, the message it obtains:

```py
    def process_msg(self):
        """Handles a message from stdin, if one is currently available."""
        line = sys.stdin.readline()
        if not line:
            return None
        
        msg = json.loads(line)

        return msg
```

If we try this out, we'll see our node obtain an ID, and the cluster IDs, from
Maelstrom!

```
$ cat store/latest/n1.log 
Online.
I am: n1
```

Since we'll be logging a lot to stderr, let's make a dedicated logging
function.

```py
import datetime
import time

# Utilities

def log(*args):
    """Helper function for logging stuff to stderr"""
    first = True
    sys.stderr.write(datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f "))
    for i in range(len(args)):
        sys.stderr.write(str(args[i]))
        if i < (len(args) - 1):
            sys.stderr.write(" ")
    sys.stderr.write('\n')
```

Now we can get rid of the string coercion and newlines in `main`:

```py
    def main(self):
        """Entry point"""
        log('Online.')

        # Handle initialization message
        msg = self.net.process_msg()
        body = msg['body']
        if body['type'] != 'raft_init':
            raise RuntimeError("Unexpected message " + pformat(msg))

        self.node_id = body['node_id']
        self.node_ids = body['node_ids']
        log('I am:', self.node_id)
```

Now we get nice timestamps!

```
$ cat store/latest/n1.log
2018-06-06 18:38:17.085793 Online.
2018-06-06 18:38:17.087497 I am: n1
```

## Event Loops & Message Handlers

We are *eventually* going to have to process more than one message. Let's turn
our main function into a loop:

```py
import traceback

...

    def main(self):
        """Mainloop."""
        log('Online.')

        while True:
            try:
                msg = self.net.process_msg()
                body = msg['body']
                log('Received\n' + pformat(msg))

                if body['type'] == 'raft_init':
                    # Handle initialization message
                    self.node_id = body['node_id']
                    self.node_ids = body['node_ids']
                    log('I am:', self.node_id)
                else:
                    raise RuntimeError("Unexpected message " + pformat(msg))
            except KeyboardInterrupt:
                log("Aborted by interrupt!")
                break
            except:
                log("Error!", traceback.format_exc())
```

This is a simple event loop; we receive messages, abort on ^C, and log errors
to the console. Let's try it out:

```
$ ./raft.py
2018-06-06 18:53:03.519818 Online.
{}
2018-06-06 18:53:04.926912 Error! Traceback (most recent call last):
  File "./raft.py", line 54, in main
    body = msg['body']
KeyError: 'body'

{"body": {"type": "hi"}}
2018-06-06 18:53:49.678070 Received
{u'body': {u'type': u'hi'}}
2018-06-06 18:53:49.678323 Error! Traceback (most recent call last):
  File "./raft.py", line 63, in main
    raise RuntimeError("Unexpected message " + pformat(msg))
RuntimeError: Unexpected message {u'body': {u'type': u'hi'}}

^C2018-06-06 18:54:57.152606 Aborted by interrupt!
```

These messages aren't valid, but this shows that our error and signal handling
work correctly.

Now, let's think a bit about how to handle incoming messages. We could expand
this mainloop to include a case for each message type--initialization, requests
to read, write, and cas keys--but what about messages we need to exchange with
other nodes? Raft is an RPC-oriented protocol, which means that we'll need to
send a message to a remote node and, when a response to that message arrives,
execute some code. That implies we need more than static dispatch based on
message type--we'll also need some sort of *dynamic* dispatch for RPC
responses. To track that, we'll add a pair of dispatch tables to the Net class:

```py
    def __init__(self):
        """Constructs a new network client."""
        self.handlers = {}  # A map of message types to handler functions
        self.callbacks = {} # A map of message IDs to response handlers
```

When a message arrives, we'll have `process_msg` look up a function from one of those tables--if it's got an `in_reply_to` field, we'll look at `callbacks`; otherwise, we'll look up a handler function by message `type`.

```py
class Net():
		...
    def process_msg(self):
        """Handles a message from stdin, if one is currently available."""
        line = sys.stdin.readline()
        if not line:
            return None
        
        msg = json.loads(line)
        log("Received\n" + pformat(msg))
        body = msg['body']

        handler = None
        # Look up reply handler
        if 'in_reply_to' in body:
            m = body['in_reply_to']
            handler = self.callbacks[m]
            del self.callbacks[m]

        # Fall back based on message type
        elif body['type'] in self.handlers:
            handler = self.handlers[body['type']]

        else:
            raise RuntimeError('No callback or handler for\n' + pformat(msg))

        handler(msg)
```

We'll need a way to register handlers, so let's add a function to Net for that:

```py
class Net():
		...

    def on(self, msg_type, handler):
        """Register a callback for a message of the given type."""
        if msg_type in self.handlers:
            raise RuntimeError('already have a handler for message type ' + type)

        self.handlers[msg_type] = handler
```

Now we can have `RaftNode.main` register a callback with its Net object, and
simplify the event loop: all it has to do is ask the Net to process messages.

```py
    def main(self):
        """Mainloop."""
        log('Online.')

        # Handle initialization message
        def raft_init(msg):
            body = msg['body']
            self.node_id = body['node_id']
            self.node_ids = body['node_ids']
            log('I am:', self.node_id)
        self.net.on('raft_init', raft_init)

        while True:
            try:
                self.net.process_msg()
            except KeyboardInterrupt:
                log("Aborted by interrupt!")
                break
            except:
                log("Error!", traceback.format_exc())
```

Let's verify that this program still works:

```
$ lein run test --bin raft.py --nodes n1 --rate 0 --time-limit 10
...
$ cat store/latest/n1.log 
2018-06-07 10:56:32.365975 Online.
2018-06-07 10:56:32.366269 Received
{u'body': {u'msg_id': 1,
           u'node_id': u'n1',
           u'node_ids': [u'n1'],
           u'type': u'raft_init'},
 u'dest': u'n1',
 u'src': u'c1'}
2018-06-07 10:56:32.366301 I am: n1
```

Great; we can handle our init message through a callback now. Now we need to
*reply* to that message, confirming to Maelstrom that we're ready to accept
requests.

## Responding to Requests

First things first, we need to be able to take message structures and emit them
on STDOUT, as newline-delimited JSON.

```py
class Net():
		...

    def send_msg(self, msg):
        """Sends a raw message object"""
        log('Sent\n' + pformat(msg))
        json.dump(msg, sys.stdout)
        sys.stdout.write('\n')
        sys.stdout.flush()

    def send(self, dest, body):
        """Sends a message to the given destination node with the given body."""
        self.send_msg({'src': self.node_id, 'dest': dest, 'body': body})
```

The `send` function is a helper, so we don't have to construct message
envelopes over and over again. It needs to know our node ID, so let's have the
RaftNode tell its Net about its node ID as a part of the initialization
process.


```py
class Net():
    def __init__(self):
        """Constructs a new network client."""
        self.node_id = None     # Our local node ID
        self.handlers = {}      # A map of message types to handler functions
        self.callbacks = {}     # A map of message IDs to response handlers

    def set_node_id(self, id):
        self.node_id = id

		...

class RaftNode():
		...

    def set_node_id(self, id):
        self.node_id = id
        self.net.set_node_id(id)

    def main(self):
        """Mainloop."""
        log('Online.')

        # Handle initialization message
        def raft_init(msg):
            body = msg['body']
            self.set_node_id(body['node_id'])
            self.node_ids = body['node_ids']
            log('I am:', self.node_id)
        self.net.on('raft_init', raft_init)

				...
```

Nothing fancy here; we've added a setting function for the node id, and updated
it in both the RaftNode and Net objects. Now, how do we *reply* to our `raft_init` message?

```
{u'body': {u'msg_id': 1,
           u'node_id': u'n1',
           u'node_ids': [u'n1'],
           u'type': u'raft_init'},
 u'dest': u'n1',
 u'src': u'c1'}
```

The request message's body includes a `msg_id`. Maelstrom expects a response
from our node (`n1`) to the client that made the request (`c1`), where the body
contains an `in_reply_to` field matching that `msg_id`--that way the client
knows that our message was in response to that particular request. Let's add a
`reply` function to Net to help with that:

```py
class Net():
		...
    def reply(self, req, body):
        """Replies to a given request message with a response body."""
        body['in_reply_to'] = req['body']['msg_id']
        self.send(req['src'], body)
```

Now, in our `raft_init` callback, we can issue a reply back to the server.

```py
        # Handle initialization message
        def raft_init(msg):
            body = msg['body']
            self.set_node_id(body['node_id'])
            self.node_ids = body['node_ids']
            log('I am:', self.node_id)
            self.net.reply(msg, {'type': 'raft_init_ok'})

        self.net.on('raft_init', raft_init)
```

If we re-run the test now, the setup process no longer times out:

```
lein run test --bin raft.py --nodes n1 --rate 0 --time-limit 10
INFO [2018-06-07 11:48:12,916] jepsen node n1 - maelstrom.process launching raft.py []
INFO [2018-06-07 11:48:13,012] jepsen worker 0 - jepsen.core Starting worker 0
INFO [2018-06-07 11:48:13,012] jepsen nemesis - jepsen.core Starting nemesis
INFO [2018-06-07 11:48:13,012] jepsen worker 0 - jepsen.core Running worker 0
INFO [2018-06-07 11:48:13,012] jepsen nemesis - jepsen.core Running nemesis
INFO [2018-06-07 11:48:13,018] jepsen worker 0 - jepsen.core Stopping worker 0
INFO [2018-06-07 11:48:23,015] jepsen nemesis - jepsen.util :nemesis	:info	:start	nil
INFO [2018-06-07 11:48:23,021] jepsen nemesis - jepsen.util :nemesis	:info	:start	[:isolated {"n1" #{}}]
INFO [2018-06-07 11:48:23,022] jepsen nemesis - jepsen.core Stopping nemesis
INFO [2018-06-07 11:48:23,022] jepsen test runner - jepsen.core Run complete, writing
INFO [2018-06-07 11:48:23,049] jepsen test runner - jepsen.core Analyzing...
INFO [2018-06-07 11:48:23,057] jepsen test runner - jepsen.core Analysis complete
INFO [2018-06-07 11:48:23,066] jepsen results - jepsen.store Wrote /home/aphyr/maelstrom/store/maelstrom/20180607T114812.000-0500/results.edn
INFO [2018-06-07 11:48:23,068] jepsen node n1 - maelstrom.core Tearing down n1
INFO [2018-06-07 11:48:24,011] jepsen test runner - jepsen.core {:perf
 {:latency-graph {:valid? true},
  :rate-graph {:valid? true},
  :valid? true},
 :timeline {:valid? true, :results {}, :failures []},
 :linear {:valid? true, :results {}, :failures []},
 :valid? true}


Everything looks good! ヽ(‘ー`)ノ
```

We haven't *done* anything with the server yet, but it does start up and acknowledge its initialization phase!

```
$ cat store/latest/n1.log 
2018-06-07 11:48:12.997828 Online.
2018-06-07 11:48:12.998207 Received
{u'body': {u'msg_id': 1,
           u'node_id': u'n1',
           u'node_ids': [u'n1'],
           u'type': u'raft_init'},
 u'dest': u'n1',
 u'src': u'c1'}
2018-06-07 11:48:12.998243 I am: n1
2018-06-07 11:48:12.998383 Sent
{'body': {'in_reply_to': 1, 'type': 'raft_init_ok'},
 'dest': u'c1',
 'src': u'n1'}
```

And there's our confirmation message going out. Excellent!

In the next chapter, we'll turn this server into a tiny key-value store.
