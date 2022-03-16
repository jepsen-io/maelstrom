# Counters

In this section, we'll build an eventually consistent counter which supports
increments, then extend it to support decrements as well.

## G-Counter

The [G-counter workload](/doc/workloads.md#workload-g-counter) defines a basic
API for a single counter. Clients issue `add` operations to increment the
counter value, and `read` operations to get the current counter value.
Maelstrom expects that the counter's final value is equal to the sum of all
adds (taking into account that some increments may or may not have happened).

We're going to copy our G-set server, and replace its set with a grow-only
counter: a G-counter.

```sh
cp g_set.rb counter.rb
```

Just like we defined a `GSet` class for representing grow-only sets, we'll
create a `GCounter` class for our counters. G-counters encode their value as a
vector (in our case, a map) of counters, one per node: the effective value is
the sum of the counters for each node.

```rb
class GCounter
  attr_reader :counters

  def initialize(counters = {})
    @counters = counters
  end
```

We'll serialize our GCounters directly as maps of node IDs to the number of
increments on that particular node.

```rb
  def from_json(json)
    GCounter.new json
  end

  def to_json
    @counters
  end
```

To read the effective total of a G-counter, we sum across the all values in its map:

```rb
  # The effective value of a G-counter is the sum of its values.
  def read
    @counters.values.reduce(0) do |sum, x|
      sum + x
    end
  end
```

And to combine two counters, we take the highest counter observed on each node.

```rb
  # Merging two G-counters means taking the maxima of corresponding hash
  # elements.
  def merge(other)
    GCounter.new(@counters.merge(other.counters) { |k, v1, v2|
      [v1, v2].max
    })
  end
```

Finally, we need a way to actually increment a counter. We'll define an `add` method which takes a node ID, and the amount to increment by.

```rb
  # Adding a value to a counter means incrementing the value for this
  # node_id.
  def add(node_id, delta)
    counters = @counters.dup
    counters[node_id] = (counters[node_id] || 0) + delta
    GCounter.new counters
  end
end
```

Now, we'll rename GSetServer to CounterServer, and replace its CRDT with a
G-counter. The only change we need to make to the message handlers is what to
do with an `add` message: we extract the delta from the message, and add it to
our local node id's counter.

```rb
class CounterServer
  attr_reader :node
  def initialize
    @node = Node.new
    @lock = Mutex.new
    @crdt = GCounter.new

    @node.on "add" do |msg|
      @lock.synchronize do
        @crdt = @crdt.add(@node.node_id, msg[:body][:delta])
      end
      @node.reply! msg, type: "add_ok"
    end

    @node.on "read" do |msg|
      @node.reply! msg, type: "read_ok", value: @crdt.read
    end

    @node.on "replicate" do |msg|
      other = @crdt.from_json(msg[:body][:value])
      @lock.synchronize do
        @crdt = @crdt.merge(other)
      end
    end

    @node.every 5 do
      @node.log "Replicating current value #{@crdt.to_json}"
      @node.node_ids.each do |n|
        # Don't replicate to self
        unless n == @node.node_id
          @node.send! n, type: "replicate", value: @crdt.to_json
        end
      end
    end
  end
end

CounterServer.new.node.main!
```

Let's try this out with a workload of increments, and see what happens:

```clj
$ ./maelstrom test -w g-counter --bin counter.rb --time-limit 20 --rate 10
...
 :workload {:valid? true,
            :errors nil,
            :final-reads (152 152 152 152 152),
            :acceptable ([152 152])},
 :valid? true}


Everything looks good! ヽ(‘ー`)ノ
```

Maelstrom adds random values, and at the end of the test, performs a final read
on each node. Those reads all observed the same value: 152. Moreover, that
value was within the acceptable range of outcomes: [152, 152].

## PN-Counters

What happens if we allow *decrements* of the value? Let's use the [pn-counter](/doc/workloads.md#workload-pn-counter) workload and see.

```clj
$ ./maelstrom test -w pn-counter --bin counter.rb --time-limit 20 --rate 10
...
            :final-reads (11 11 11 11 11),
            :acceptable ([-38 -38])},
 :valid? false}


Analysis invalid! (ﾉಥ益ಥ）ﾉ ┻━┻
```

Well that's not great! Our value was *supposed* to be -38, but wound up as 11
instead. What happened?

There's nothing obviously sign-related in our definition of a G-counter: the
`add` function adds positive and negative deltas the same way. If we start with
the counter map `{"n1" 2}`, and call `.add("n1", -5})`, the resulting map is
`{"n1" -3}`. That's fine, right?

But what happens if we were to *merge* these values? We'd pick the maximum
value for n1, which would be `2`, not `-3`. The negative increment is
effectively *lost*.

To solve this problem, a PN-counter (logically) uses *two* G-counters: one for
increments, and one for decrements.

```rb
class PNCounter
  attr_reader :inc, :dec
  def initialize(inc = GCounter.new, dec = GCounter.new)
    @inc = inc
    @dec = dec
  end
```

We encode this structure as a simple JSON map:

```rb
  def from_json(json)
    PNCounter.new(@inc.from_json(json["inc"]),
                  @dec.from_json(json["dec"]))
  end

  def to_json
    {inc: @inc.to_json,
     dec: @dec.to_json}
  end
```

To get the effective value of a PN-counter, we subtract the decrements from the increments:

```rb
  def read
    @inc.read - @dec.read
  end
```

And to combine two PN-counters together, we merge their respective G-counters:

```rb
  def merge(other)
    PNCounter.new @inc.merge(other.inc), @dec.merge(other.dec)
  end
```

Finally, we need a more sophisticated `add` function. Adds of negative numbers
increment the `dec` G-counter, and positive adds go to the `inc` G-counter.

```rb
  def add(node_id, delta)
    if 0 <= delta
      PNCounter.new @inc.add(node_id, delta), @dec
    else
      PNCounter.new @inc, @dec.add(node_id, -delta)
    end
  end
end
```

It's *nice* that we can compose two G-counters together like this. It suggests
that we could build up ever-more-complex datatypes, and so long as their pieces
are CRDTs, their composition might be one too. We might have a Person datatype
which combines a G-counter for "number of dogs petted" and a OR-set for "ice
creams in freezer, and by merging their fields appropriately, that Person would be a CRDT as well.

Anyway, let's try swapping out our server's G-counter for a PN-counter, and see what happens.

```rb
class CounterServer
  attr_reader :node
  def initialize
    @node = Node.new
    @lock = Mutex.new
    @crdt = PNCounter.new   # Now a PNCounter
    ...
```

```clj
$ ./maelstrom test -w pn-counter --bin counter.rb --time-limit 20 --rate 10
...
 :workload {:valid? true,
            :errors nil,
            :final-reads (-86 -86 -86 -86 -86),
            :acceptable ([-86 -86])},
 :valid? true}


Everything looks good! ヽ(‘ー`)ノ
```

Now we can handle decrements as well! Is it partition-tolerant?

```clj
$ ./maelstrom test -w pn-counter --bin counter.rb --time-limit 30 --rate 10 --nemesis partition
...
 :workload {:valid? true,
            :errors nil,
            :final-reads (-32 -32 -32 -32 -32),
            :acceptable ([-32 -32])},
 :valid? true}


Everything looks good! ヽ(‘ー`)ノ
```

It is! We've successfully built an AP counter service!

In the [next chapter](/doc/05-datomic/01-single-node.md), we'll build a transactional key-value store on top of
existing Maelstrom services.
