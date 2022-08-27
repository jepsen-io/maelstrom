package maelstrom.txnListAppend;

import io.lacuna.bifurcan.IMap;
import io.lacuna.bifurcan.List;

// A State is a map of longs to lists of longs.
public record State(IMap<Long, List<Long>> map) {
  // A pair of a state and transaction together.
  public record StateTxn(State state, Txn txn) {
  }

  // Applies a transaction to a state, returning a new State and completed Txn.
  public StateTxn apply(Txn txn) {
    // A mutable copy of the transaction operations, which we'll fill in with
    // completed ops as we go.
    List<IOp> ops2 = new List<IOp>().linear();
    // A mutable copy of our state map, which we'll evolve as we go.
    IMap<Long, List<Long>> map2 = map.linear();

    for (final IOp op : txn.ops()) {
      final String f = op.getFun();
      final Long k = op.getKey();
      final Object v = op.getValue();
      switch (f) {
        case "append":
          map2 = map2.put(k, map2.get(op.getKey(), new List<Long>()).addLast((Long) v));
          ops2 = ops2.addLast(op);
          break;

        case "r":
          ops2 = ops2.addLast(new ReadOp(f, k, map2.get(k, null)));
          break;

        default:
          throw new IllegalArgumentException("Unexpected op fun " + f);
      }
    }

    return new StateTxn(new State(map2.forked()), new Txn(ops2.forked()));
  }
}