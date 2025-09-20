package lab.txnListAppend;

import io.lacuna.bifurcan.IList;

// A thunk stores a list of numbers under a unique string ID.
public record Thunk(String id, IList<Long> value) {
}
