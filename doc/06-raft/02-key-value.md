# A Key-Value Store

Raft replicates arbitrary state machines, but Maelstrom tests one particular
type of state machine: a key-value store. KV stores are essentially maps, so we'll use a Python map to represent one:

```py
class KVStore():
  def __init__(self):
    self.state = {}
```

As per Maelstrom's [protocol](https://github.com/jepsen-io/maelstrom#protocol)
There are three types of operations Maelstrom expects from us: reads, writes,
and compare-and-set operations. The protocol gives the full semantics, but to refresh our memory:

```clj
{"type"       "read"
 "msg_id"     An integer
 "key"        A string: the key the client would like to read}

{"type"     "write"
 "msg_id"   An integer
 "key"      A string: the key the client would like to write
 "value"    A string: the value the client would like to write}

{"type"     "cas"
 "msg_id"   An integer
 "key"      A string: the key the client would like to write
 "from"     A string: the value that the client expects to be present
 "to"       A string: the value to write if and only if the value is `from`}
```

Our state machine needs to take these operations and *transition* to a new
state.

```py
class KVStore():
	...

   def apply(self, op):
        """Applies an op to the state machine, and returns a response message."""
        t = op['type']
        k = op['key']
```

For reads, we'll check to see if the key being read is in our state, and if so,
return the value we have for that key. Otherwise, we'll return a `not found`
error.

```py
        # Handle state transition
        if t == 'read':
            if k in self.state:
                res = {'type': 'read_ok', 'value': self.state[k]}
            else:
                res = {'type': 'error', 'code': 20, 'text': 'not found'}
```

For writes, we'll update our local state with the value from the message:

```py
        elif t == 'write':
            self.state[k] = op['value']
            res = {'type': 'write_ok'}
```

For compare-and-set, we'll do the same not-found check, but also verify
that the current value we have is the same as the `from` value in the request.
If it matches, we'll update our value to the `to` value.

```py
        elif t == 'cas':
            if k not in self.state:
                res = {'type': 'error', 'code': 20, 'text': 'not found'}
            elif self.state[k] != op['from']:
                res = {
                    'type': 'error',
                    'code': 22,
                    'text': 'expected ' + str(op['from']) + ' but had ' + str(self.state[k])
                    }
            else:
                self.state[k] = op['to']
                res = {'type': 'cas_ok'}
```

To finish off, we'll log the current KV state, and construct a reply back to whoever sent us this request.

```py
        log('KV:\n' + pformat(self.state))

        # Construct response
        res['in_reply_to'] = op['msg_id']
        return {'dest': op['client'], 'body': res}
```

Now, let's handle client requests by routing them through our state machine.

```py
class RaftNode():
    def __init__(self):
				...
        self.state_machine = KVStore()

		...

		def main(self):
				...

        # Handle client KV requests
        def kv_req(msg):
            op = msg['body']
            op['client'] = msg['src']
            res = self.state_machine.apply(op)
            self.net.send(res['dest'], res['body'])

        self.net.on('read', kv_req)
        self.net.on('write', kv_req)
        self.net.on('cas', kv_req)
```

As we build up more message handlers, it starts to seem a little odd to have
that be a part of the mainloop. Let's pull handler setup out into its own
function:

```py
class RaftNode():
    def __init__(self):
        self.node_id = None     # Our node ID
        self.node_ids = None    # The set of node IDs
        
        self.state = 'nascent'  # One of nascent, follower, candidate, or leader
    
        self.state_machine = KVStore()
        self.net = Net()
        self.setup_handlers()

		...

    # Message handlers

    def setup_handlers(self):
        """Registers message handlers with this node's client"""
    
        # Handle initialization message
        def raft_init(msg):
            body = msg['body']
            self.set_node_id(body['node_id'])
            self.node_ids = body['node_ids']
            log('I am:', self.node_id)
            self.net.reply(msg, {'type': 'raft_init_ok'})

        self.net.on('raft_init', raft_init)

        # Handle client KV requests
        def kv_req(msg):
            op = msg['body']
            op['client'] = msg['src']
            res = self.state_machine.apply(op)
            self.net.send(res['dest'], res['body'])

        self.net.on('read', kv_req)
        self.net.on('write', kv_req)
        self.net.on('cas', kv_req)

    def main(self):
        """Mainloop."""
        log('Online.')
                
        while True:
            try:
                self.net.process_msg()
            except KeyboardInterrupt:
                log("Aborted by interrupt!")
                break
            except:
                log("Error!", traceback.format_exc())
```

Now we'll re-run Maelstrom, but increasing the rate of client requests from 0
to 1 per second per client.

```
$ lein run test --bin raft.py --nodes n1 --rate 1 --time-limit 10
...
INFO [2018-06-07 13:14:12,057] jepsen worker 0 - jepsen.util 0	:invoke	:write	[0 4]
INFO [2018-06-07 13:14:12,062] jepsen worker 0 - jepsen.util 0	:ok	:write	[0 4]
INFO [2018-06-07 13:14:12,581] jepsen worker 0 - jepsen.util 0	:invoke	:cas	[0 [4 0]]
INFO [2018-06-07 13:14:12,584] jepsen worker 0 - jepsen.util 0	:ok	:cas	[0 [4 0]]
INFO [2018-06-07 13:14:13,626] jepsen worker 0 - jepsen.util 0	:invoke	:read	[0 nil]
INFO [2018-06-07 13:14:13,629] jepsen worker 0 - jepsen.util 0	:ok	:read	[0 0]
...

Everything looks good! ヽ(‘ー`)ノ
```

Hey! It's a KV store! `:invoke :write [0 4]` means a Maelstrom client started a
write, setting key 0 to the value 4. The `:ok` response on the next line shows
that Maelstrom acknowledged that write successfully. Then we have a
compare-and-set of key 0 from 4 to 0, and a read, which shows the current
value, 0. This is a legal history, so Maelstrom informs us that everything
looks good.

## But Does it Distribute?

Of course, this is with a single server, and that server is singlethreaded, so linearizability is *trivial*. What if we run two nodes?

```
$ lein run test --bin raft.py --nodes n1,n2 --rate 1 --time-limit 10
...
 :linear
 {:valid? false,
  :results
  {0
   {:valid? false,
    :configs
    ({:model {:value 2},
      :last-op
      {:process 1,
       :type :ok,
       :f :write,
       :value 2,
       :index 31,
       :time 9122816645},
      :pending
      [{:process 0,
        :type :invoke,
        :f :read,
        :value 0,
        :index 32,
        :time 9767190691}]}),
    :final-paths
    ([{:op
       {:process 1,
        :type :ok,
        :f :write,
        :value 2,
        :index 31,
        :time 9122816645},
       :model {:value 2}}
      {:op
       {:process 0,
        :type :ok,
        :f :read,
        :value 0,
        :index 33,
        :time 9770099073},
       :model {:msg "can't read 0 from register 2"}}]),
    :previous-ok
    {:process 1,
     :type :ok,
     :f :write,
     :value 2,
     :index 31,
     :time 9122816645},
    :last-op
    {:process 1,
     :type :ok,
     :f :write,
     :value 2,
     :index 31,
     :time 9122816645},
    :op
    {:process 0,
     :type :ok,
     :f :read,
     :value 0,
     :index 33,
     :time 9770099073}}},
  :failures [0]},
 :valid? false}


Analysis invalid! (ﾉಥ益ಥ）ﾉ ┻━┻
```

This is Maelstrom's representation of a linearizability failure on key 0. The
`:op` field tells us that process 0's read of the value 0 was impossible, given
the current states of the system.

What were those states? `:configs` shows some of the possible configurations of key 0 at that point in time. There's only one, and its state was `2`:

```clj
    :configs
    ({:model {:value 2},
```

... and if we look at `:final-paths`, it shows us some of the ways we could
have *tried* to execute process 0's read of 0, and what would have happened in
each case:

```clj
    :final-paths
    ([{:op
       {:process 1,
        :type :ok,
        :f :write,
        :value 2,
        :index 31,
        :time 9122816645},
       :model {:value 2}}
      {:op
       {:process 0,
        :type :ok,
        :f :read,
        :value 0,
        :index 33,
        :time 9770099073},
       :model {:msg "can't read 0 from register 2"}}]),
```

We execute a write of 2, then read the current value and observe 0. That's
impossible: we expected to read 2!

To see this visually, Jepsen produces (where possible) visualizations of the
failure:

```
$ open store/latest/independent/0/timeline.html
```

This shows us a visual representation of all the operations Maelstrom executed.
Time flows from top to bottom, and each vertical track is a single client
process. To see a more detailed explanation of the nonlinearizable fault:

```
$ open store/latest/independent/0/linear.svg
```

This is an interactive SVG rendering of the final moments of the system, right
before we ran out of linearizable paths. Time flows left to right, and
processes are top to bottom. You can hover over paths from op to op to see how
each path was (eventually) impossible to follow.

In both plots, blue indicates success, pink indicates a known failure, and
orange represents an indeterminate result.

Jepsen includes a web server for browsing the results of tests. Run `lein run
serve`, and head to [http://localhost:8080](http://localhost:8080) to see a
list of tests in reverse chronological order.

To sum up: we've written a simple networked key-value store. Because none of
its state is replicated between nodes, Maelstrom can show that it isn't
linearizable. In the following chapters, we'll *replicate* that state using the
Raft protocol, starting with leader election.
