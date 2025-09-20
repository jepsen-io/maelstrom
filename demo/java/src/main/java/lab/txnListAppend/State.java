package lab.txnListAppend;

import io.lacuna.bifurcan.IMap;
import io.lacuna.bifurcan.List;
import io.lacuna.bifurcan.Map;

import io.lacuna.bifurcan.IList;

// The pure state machine for list-append transactions. Stores a
// map of integer keys to lists of integer values.
public record State(IMap<Long, IList<Long>> map) {
    public State() {
        this(new Map<Long, IList<Long>>().forked());
    }            

    // A tuple of a state and transaction together; used as our return type for
    // apply(txn).
    public record StateTxn(State state, Txn txn) {
    }

    // Applies a transaction to this state, returning a state and resulting txn
    public StateTxn apply(Txn txn) {
        // New txn
        List<Mop> ops2 = new List<Mop>().linear();
        // New state
        IMap<Long, IList<Long>> map2 = map.linear();

        for (final Mop mop : txn.mops()) {
            final String f = mop.fun();
            final Long k = mop.key();
            final Object v = mop.value();
            final IList<Long> currentValue = map2.get(k, new List<Long>());
            switch (f) {
                case "append":
                    map2 = map2.put(k, currentValue.addLast((Long) v));
                    ops2 = ops2.addLast(mop);
                    break;
                case "r":
                    ops2 = ops2.addLast(new ReadMop(f, k, currentValue));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown f " + f);
            }
        }

        return new StateTxn(
                new State(map2.forked()),
                new Txn(ops2.forked()));
    }
}
