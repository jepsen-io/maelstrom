package maelstrom.txnListAppend;

import com.eclipsesource.json.JsonArray;

import maelstrom.IJson;

// Represents a tranasction operation
public interface IOp extends IJson {
  public String getFun();

  public Long getKey();

  public Object getValue();

  // Constructs either an append or read op from a JSON array.
  public static IOp from(JsonArray json) {
    final String fun = json.get(0).asString();
    switch (fun) {
      case "append":
        return AppendOp.from(json);
      case "r":
        return ReadOp.from(json);
      default:
        throw new IllegalArgumentException("Unknown op fun: " + fun);
    }
  }
}