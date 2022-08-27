package maelstrom.txnListAppend;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonValue;

import io.lacuna.bifurcan.List;
import io.lacuna.bifurcan.Set;
import maelstrom.IJson;
import maelstrom.JsonUtil;

// A transaction is a list of operations.
public record Txn(List<IOp> ops) implements IJson {
  // Build a transaction from a JSON array
  public Txn(JsonArray json) {
    this(JsonUtil.toBifurcanList(json, (op) -> {
      return IOp.from(op.asArray());
    }));
  }

  @Override
  public JsonValue toJson() {
    return JsonUtil.toJson(ops, (op) -> {
      return op.toJson();
    });
  }

  // The set of all keys we need to read in order to execute a transaction.
  public final Set<Long> readSet() {
    Set<Long> keys = new Set<Long>().linear();
    for (IOp op : ops) {
      keys.add(op.getKey());
    }
    return keys.forked();
  }

  // The set of all keys we need to write in order to execute a transaction.
  public final Set<Long> writeSet() {
    Set<Long> keys = new Set<Long>().linear();
    for (IOp op : ops) {
      if (! (op instanceof ReadOp)) {
        keys.add(op.getKey());
      }
    }
    return keys.forked();
  }
}