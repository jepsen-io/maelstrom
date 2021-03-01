# A Key-Value Store

The [Raft](https://raft.github.io/raft.pdf) algorithm provides a replicated
linearizable log which can drive an arbitrary state machine. In this chapter,
we'll follow the Raft paper to implement our own Raft nodes.

Our state machine will be a simple key-value store. Maelstrom will interact
with this key-value store via the [lin-kv](/doc/workloads.md#workload-lin-kv)
workload, which specifies three basic types of operations:

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
state. We'll define a Map class which takes these kinds of request bodies
and returns a new Map, plus the appropriate response message. In a fresh file, `raft.rb`, we begin:

```rb
#!/usr/bin/env ruby

require_relative 'node.rb'

class Map
  def initialize(map = {})
    @map = map
  end
```

For reads, we'll check to see if the key being read is in our state, and if so,
return the value we have for that key. Otherwise, we'll return a `not found`
error.

```rb
  # Applies an operation (e.g. {type: "write", key: 1, value: 2}) to this Map,
  # returning a tuple of the resulting map and completed operation.
  def apply(op)
    k = op[:key]
    case op[:type]
    when 'read'
      if @map.key? k
        [self, {type: 'read_ok', value: @map[k]}]
      else
        [self, RPCError.key_does_not_exist('not found').to_json]
      end
```

For writes, we'll update our local state with the value from the message:

```rb
    when 'write'
      [Map.new(@map.merge({k => op[:value]})), {type: 'write_ok'}]
```

For compare-and-set, we'll do the same not-found check, but also verify
that the current value we have is the same as the `from` value in the request.
If it matches, we'll update our value to the `to` value.

```rb
    when 'cas'
      if @map.key? k
        if @map[k] == op[:from]
          [Map.new(@map.merge({k => op[:to]})),
           {type: 'cas_ok'}]
        else
          [self,
           RPCError.precondition_failed(
             "expected #{op[:from]}, but had #{@map[k]}"
          ).to_json]
        end
      else
        [self, RPCError.key_does_not_exist('not found').to_json]
      end
    end
  end
end
```

Now, let's create a class for our Raft server. Just like before, we'll use a
`Node` for our network interaction. We'll store a reference to a `Map` in a
`@state_machine` variable. A `lock` synchronizes concurrent updates.

```rb
class Raft
  attr_reader :node
  def initialize
    @node = Node.new
    @lock = Monitor.new
    @state_machine = Map.new
```

In response to reads, writes, and cas requests, it applies the body of the
message to the state, and sends back a response. All three handlers have the
same pattern, which we'll call `client_req`.

```rb
    @node.on 'read'  do |m| client_req(m) end
    @node.on 'write' do |m| client_req(m) end
    @node.on 'cas'   do |m| client_req(m) end
  end

  # Handles a client RPC request
  def client_req(msg)
    @lock.synchronize do
      @state_machine, res = @state_machine.apply(msg[:body])
      @node.reply! msg, res
    end
  end
end

Raft.new.node.main!
```

As usual, we've built the single-node version of the server first, without caring about replication. Let's see if that works:

```clj
$ ./maelstrom test -w lin-kv --bin raft.rb --time-limit 10 --rate 10 --node-count 1 --concurrency 2n
...
2021-02-26 23:39:01,300{GMT}	INFO	[jepsen worker 1] jepsen.util: 1	:invoke	:write	[0 2]
2021-02-26 23:39:01,303{GMT}	INFO	[jepsen worker 1] jepsen.util: 1	:ok	:write	[0 2]
2021-02-26 23:39:01,526{GMT}	INFO	[jepsen worker 0] jepsen.util: 0	:invoke	:read	[0 nil]
2021-02-26 23:39:01,527{GMT}	INFO	[jepsen worker 0] jepsen.util: 0	:ok	:read	[0 2]
```

This shows that worker 1 executed a write of 2 to key 0, and that write
completed successfully. Then worker 0 performed a read of key 0, and obtained
the value 2! At the end of the test, we should (hopefully) get a successful
result:

```clj
 :workload {:valid? true,
            :results {0 {:linearizable {:valid? true,
                                        :configs ({:model #knossos.model.CASRegister{:value 3},
                                                   :last-op {:process 1,
                                                             :type :ok,
                                                             :f :cas,
                                                             :value [2
                                                                     3],
                                                             :index 85,
                                                             :time 9787361454},
                                                   :pending []}),
                                        :final-paths ()},
                         :timeline {:valid? true},
                         :valid? true}},
            :failures []},
 :valid? true}


Everything looks good! ヽ(‘ー`)ノ
```

Very good! The `:configs` list here shows us all the possible final
configurations of the system for key 0. Here, the last operation to execute was
a `cas` of 2 to 3, and the resulting value was `3`.

## But Does It Distribute?

Of COURSE this works with a single server. But what about multiple nodes? That
should fail to be linearizable, right?

```clj
$ ./maelstrom test -w lin-kv --bin raft.rb --time-limit 10 --node-count 2 --rate 10 --concurrency 2n
...
Analysis invalid! (ﾉಥ益ಥ）ﾉ ┻━┻
```

We expect all kinds of linearizability failures here: Maelstrom is trying to
interpret operations against two completely independent copies of a value as if
they were the same copy. Take a look at the `independent` directory: each
subdirectory corresponds to a single key. You might find the `linear.svg` plot
there suggestive. For instance, this test run produced a write of 2 followed by
a read of 4--clearly impossible without an intervening write of 4.

![](single-node-anomaly.svg)

In this plot, time flows from left to right, and each process' operations are
shown in a single horizontal track. Blue rectangles represent OK operations,
orange ones crashed (`info`) operations. The lines between rectangles show what
would happen if we tried to apply those operations in order. Here, only a
single transition is possible, and if we hover over it, it's illegal: we cannot
execute a read of 4 if the current state is 2.

So, we have a single-node key-value server. In the next chapter, we'll [start
replicating it between multiple nodes](02-leader-election.md) by building a
leader election system.
