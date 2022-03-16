# A Single Node

In this chapter, our goal is to implement a distributed, transactional
key-value store. To make things a little more interesting, we'll make our
values *lists* of integers, and our writes *appends* of integers. This data
model will enable Maelstrom to perform more sophisticated safety analyses, and
make it easier to debug when things go wrong. The transaction system we build
will support *arbitrary* operations: there's nothing here which is specific to
lists or appends.

## Transaction Structure

The workload we'll use for this chapter is called
[txn-list-append](/doc/workloads.md#workload-txn-list-append). Our servers need
to support a single RPC type `txn`, which takes an incomplete transaction (e.g.
one where reads have no values) to execute, and returns the
completed version of that transaction (i.e. with reads filled in).

```js
{"type": "txn",
 "txn": a_transaction,
 "msg_id": 123}
```

Which returns

```js
{"type": "txn_ok",
 "txn": that_transaction_completed,
 "in_reply_to": 123}
```

Each transaction is a list of *micro-operations*.

```js
[op1, op2, op3]
```

And each micro-operation is of the form [function, key, value]:

- `["r", 1, [3, 4, 5]]` denotes a read of key 1 observing the value `[3, 4, 5]`.
- `["append", 1, 6]` denotes appending 6 to key 1.

If we executed these operations in order, the resulting value of key 1 would be
`[3, 4, 5, 6]`.

Maelstrom can check a broad range of consistency models for this workload, but
the property that we'll aim for is one of the strongest: [strict
serializability](https://jepsen.io/consistency/models/strict-serializable). Informally, this means we need to ensure two things:

1. Transactions appear to execute in some total order, as if they had been
   performed one after the next.
2. That order is consistent with the real-time order of events: if we return
   `txn_ok` for transaction T1, and a client later submits transaction T2, then
   T1 must appear to execute before T2.

With this in mind, let's get started!

## Local State Machine

Let's start by creating a new file for our transactional server: `datomic.rb`.
We'll follow the same approach we have in previous chapters: creating a node
and calling its mainloop.

```rb
#!/usr/bin/env ruby

require_relative 'node.rb'

class Transactor
  attr_reader :node
  
  def initialize
    @node = Node.new
    @lock = Mutex.new
  end
end

Transactor.new.node.main!
```

```sh
$ chmod +x datomic.rb
```

Now, let's fire up Maelstrom, and see what kind of messages we get from our workload.

```clj
./maelstrom test -w txn-list-append --bin datomic.rb --time-limit 10 --log-stderr --node-count 1
...
INFO [2021-02-26 10:01:36,788] n3 stderr - maelstrom.process /home/aphyr/maelstrom/node.rb:120:in `block in main!': No handler for {:dest=>"n3", :body=>{:txn=>[["r", 9, nil], ["r", 8, nil]], :type=>"txn", :msg_id=>1}, :src=>"c14", :id=>14} (RuntimeError)
```

Right, so we're going to need a handler for the `txn` message type. Let's add
one. We'll just... send back exactly the same transaction we got, without doing anything to it. See what happens.

```rb
    @node.on 'txn' do |msg|
      txn = msg[:body][:txn]
      @node.log "\nTxn: #{txn}"
      @node.reply! msg, type: "txn_ok", txn: txn
    end
```

```clj
$ ./maelstrom test -w txn-list-append --bin datomic.rb --time-limit 10 --log-stderr --node-count 1
...
INFO [2021-02-26 10:04:41,678] n3 stderr - maelstrom.process Txn: [["append", 9, 13], ["r", 7, nil]]
```

OK, so there's a transaction with two operations: an append and a read. We're
going to need something that can go through and apply those micro-ops to our
state. How did this test run end, anyway?

```clj
 :workload {:valid? false,
            :anomaly-types (:internal),
            :anomalies {:internal ({:op {:type :ok,
                                         :f :txn,
                                         :value [[:append 9 6]
                                                 [:r 9 nil]],
                                         :time 3361561215,
                                         :process 3,
                                         :index 35},
                                    :mop [:r 9 nil],
                                    :expected [... 6]}
                                   {:op {:type :ok,
                                         :f :txn,
                                         :value [[:append 9 12]
                                                 [:r 9 nil]],
                                         :time 5249106084,
                                         :process 0,
                                         :index 53},
                                    :mop [:r 9 nil],
                                    :expected [... 12]}
                                   {:op {:type :ok,
                                         :f :txn,
                                         :value [[:append 9 16]
                                                 [:r 9 nil]],
                                         :time 9097287963,
                                         :process 4,
                                         :index 87},
                                    :mop [:r 9 nil],
                                    :expected [... 16]})},
            :not #{:read-atomic},
            :also-not #{:ROLA
                        :causal-cerone
                        :parallel-snapshot-isolation
                        :prefix
                        :serializable
                        :snapshot-isolation
                        :strict-serializable
                        :strong-session-serializable
                        :strong-session-snapshot-isolation
                        :strong-snapshot-isolation}},
 :valid? false}


Analysis invalid! (ﾉಥ益ಥ）ﾉ ┻━┻
```

OK, neat! So this is *not* strict-serializable. But it's also not a bunch of
other things--in particular, this history wasn't even `read-atomic`. We know
that because it exhibited *internal* consistency violations--anomalies within
the scope of a single transaction. For example, we executed:

```clj
                                   {:op {:type :ok,
                                         :f :txn,
                                         :value [[:append 9 6]
                                                 [:r 9 nil]],
                                         :time 3361561215,
                                         :process 3,
                                         :index 35},
                                    :mop [:r 9 nil],
                                    :expected [... 6]}
```

This transaction appended 6 to key 9, and then read key 9, and observed its
state to be... `nil`. That's got to be wrong! In particular, the micro-op
(`mop`) `[:r 9 nil]` was invalid: it should have seen *some* kind of list
ending in a 6. This happened because we didn't actually *do* any kind of read:
we just returned the transaction exactly as it arrived, with all read values
left `nil`.

So, we're going to need *state*. Local state, to start; we'll worry about
sharing it between nodes later. Let's create a `state` class to store our
state. Inside, we'll store a mutable hashmap of keys to values.

```rb
class State
  def initialize
    @state = {}
  end
```

Next we need a method, `State.transact!`, that can *execute* a transaction,
mutating State, and returning a completed version of that transaction with the
values of any reads. We'll run through the transaction's operations
sequentially.

```rb
  def transact!(txn)
    txn2 = []
    txn.each do |op|
      f, k, v = op
      case f
      when 'r'
        txn2 << [f, k, (@state[k] or [])]
      when 'append'
        txn2 << op
        list = (@state[k] or [])
        list << v
        @state[k] = list
      end
    end
    txn2
  end
end
```

Now we can create a `State` object in our `Transactor`, and use its `transact!`
method to update the state and produce a resulting transaction.

```rb
class Transactor
  attr_reader :node

  def initialize
    @node = Node.new
    @lock = Mutex.new
    @state = State.new

    @node.on 'txn' do |msg|
      txn = msg[:body][:txn]
      @node.log "\nTxn: #{txn}"
      txn2 = @lock.synchronize do
        @state.transact! txn
      end
      @node.reply! msg, type: "txn_ok", txn: txn2
    end
  end
end
```

```clj
./maelstrom test -w txn-list-append --bin datomic.rb --time-limit 10 --node-count 1
...
Everything looks good! ヽ(‘ー`)ノ
```

Our code actually contains a bug. Can you guess what it might be? Let's try
upping the number of concurrent clients, the request rate, and running for a
tad longer:

```clj
$ ./maelstrom test -w txn-list-append --bin datomic.rb --time-limit 30 --node-count 1 --concurrency 10n --rate 100
...
 :workload {:valid? false,
            :anomaly-types (:internal),
            :anomalies {:internal ({:op {:type :ok,
                                         :f :txn,
                                         :value [[:r
                                                  9
                                                  [1 2 3 4 5 6 7]]
                                                 [:append 9 6]
                                                 [:append 9 7]
                                                 [:r
                                                  9
                                                  [1 2 3 4 5 6 7]]],
                                         :time 56728387,
                                         :process 8,
                                         :index 13},
                                    :mop [:r 9 [1 2 3 4 5 6 7]],
```

Well THAT'S neat! A single transaction executed a read of key 9, observing [1 2
3 4 5 6 7]. It then appended 6 and 7 to key 9, and read... [1 2 3 4 5 6 7]
again. Did we *fail* to append values? Or somehow read them from the future?
Let's look at the full history for key 9:

```clj
$ cat store/latest/history.txt | grep :ok | egrep --color '(append|r) 9 '
8	:ok	:txn	[[:r 6 nil] [:append 9 1] [:append 9 2]]
0	:ok	:txn	[[:append 7 1] [:r 8 nil] [:r 9 [1 2]]]
1	:ok	:txn	[[:r 7 [1]] [:append 9 3]]
9	:ok	:txn	[[:append 9 4] [:r 9 [1 2 3 4]]]
3	:ok	:txn	[[:r 9 [1 2 3 4]] [:r 7 [1]] [:r 7 [1]] [:r 8 nil]]
0	:ok	:txn	[[:r 9 [1 2 3 4 5]] [:r 6 nil] [:append 9 5]]
8	:ok	:txn	[[:r 9 [1 2 3 4 5 6 7]] [:append 9 6] [:append 9 7] [:r 9 [1 2 3 4 5 6 7]]]
```

This actually happened several times--and every time, we saw a value that was
theoretically appended later in the history. We've been bitten by mutability!

```rb
class State
  def transact!(txn)
    ...
      when 'append'
        txn2 << op
        list = (@state[k] or [])
        list << v
        @state[k] = list
      end
```

This instruction `list << v` *mutates* `list` in-place, which changes its value
in *every* read of that key. We actually want to construct a copy of the array
instead.

```rb
      when 'append'
        txn2 << op
        list = (@state[k].clone or [])
        list << v
        @state[k] = list
      end
```

```clj
$ ./maelstrom test -w txn-list-append --bin datomic.rb --time-limit 30 --node-count 1 --concurrency 10n --rate 100
...
Everything looks good! ヽ(‘ー`)ノ
```

Excellent. What happens when we add more nodes?

```clj
$ ./maelstrom test -w txn-list-append --bin datomic.rb --time-limit 10 --node-count 2
...
            :not #{:read-atomic :read-committed},
            :also-not #{:ROLA
                        :causal-cerone
                        :consistent-view
                        :cursor-stability
                        :forward-consistent-view
                        :monotonic-atomic-view
                        :monotonic-snapshot-read
                        :monotonic-view
                        :parallel-snapshot-isolation
                        :prefix
                        :repeatable-read
                        :serializable
                        :snapshot-isolation
                        :strict-serializable
                        :strong-session-serializable
                        :strong-session-snapshot-isolation
                        :strong-snapshot-isolation
                        :update-serializable}},

```

Well at least we don't have any *internal* consistency anomalies, but we're still not read-atomic, or read-committed, for that matter. Why? Because we observed *incompatible orders*.


```clj
                        :incompatible-order ({:key 7,
                                              :values [[3 4] [1 2]]}
                                             {:key 9,
                                              :values [[4 5 7]
                                                       [1 2 3]]}
                                             {:key 10,
                                              :values [[8] [1]]}
                                             {:key 8,
                                              :values [[3 4] [1 2 5]]})},
```

At various points in the history, a read of key 7 returned the values `[3 4]`,
but *also* `[1 2]`. That can't possibly be true, if there actually were a
single list for key 7, and we only appended to it. And indeed, there *isn't* a
single key 7 any more: we have a completely different state on each node. What
we need to do is *share* that state somehow.

Next: [Shared State](02-shared-state.md).
