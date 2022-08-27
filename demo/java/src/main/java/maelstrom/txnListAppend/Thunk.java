package maelstrom.txnListAppend;

import io.lacuna.bifurcan.List;

// A thunk stores a list of numbers under a unique string ID.
public record Thunk(String id, List<Long> value) {
}