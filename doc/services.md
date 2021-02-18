# Services

Services are Maelstrom-provided nodes which offer things like 'a
linearizable key-value store', 'a source of sequentially-assigned
timestamps', 'an eventually-consistent immutable key-value store', 'a
sequentially consistent FIFO queue', and so on. Your nodes can use these as
primitives for building more sophisticated systems.

For instance, if you're trying to build a transactional, serializable
database, you might build it as a layer on top of an existing linearizable
per-key kv store--say, several distinct Raft groups, one per shard. In
Maelstrom, you'd write your nodes to accept transaction requests, then (in
accordance with your chosen transaction protocol) make your own key-value
requests to the `lin-kv` service.

To use a service, simply send an [RPC request](protocol.md) to the node ID of
the service you want to use: for instance, `lin-kv`. The service will send you
a response message. For an example, see [lin_kv_proxy.rb](/demo/ruby/lin_kv_proxy.rb).

## lin-kv

The `lin-kv` service offers a linearizable key-value store, which has exactly
the same API as the [lin-kv workload](workloads.md#workload-lin-kv). It offers
write, read, and compare-and-set operations on individual keys.

## seq-kv

A sequentially consistent key-value store. All operations appear to take place
in a total order. Each client observes a strictly monotonic order of
operations. However, clients may interact with past states of the key-value
store, provided it does not violate these ordering constraints.

This is *more* than simply stale reads: update operations may interact with
past states, so long as doing so would not violate the total-order constraints.
For example, the following non-concurrent history is legal:

1. `n1` writes x = 1
2. `n2` compare-and-sets x from 1 to 2
3. `n1` writes x = 1
4. `n2` reads x = 1

This is legal because `n1`'s second write can be re-ordered to the past without
violating the per-process ordering constraint, and retaining identical
semantics.

1. `n1` writes x = 1
2. `n1` writes x = 1
3. `n2` compare-and-sets x from 1 to 2
4. `n2` reads x = 1

## lin-tso

A linearizable timestamp oracle, which produces a stream of monotonically
increasing integers, one for each request. This is a key component in some distributed transaction algorithms--notably, Google's Percolator.

Responds to a request like:

```json
{
  "type": "ts"
}
```

with:

```json
{
  "type": "ts_ok",
  "ts": 123
}
```
