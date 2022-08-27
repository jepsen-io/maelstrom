package maelstrom.txnListAppend;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonValue;

// An append operation is a triple of a function, a key, and a long value.
public record AppendOp(String fun, Long key, Long value) implements IOp {
  public static AppendOp from(JsonArray json) {
    return new AppendOp(json.get(0).asString(),
        json.get(1).asLong(),
        json.get(2).asLong());
  }

  @Override
  public String getFun() {
    return fun;
  }

  @Override
  public Long getKey() {
    return key;
  }

  @Override
  public Object getValue() {
    return value;
  }

  @Override
  public JsonValue toJson() {
    return Json.array().add(fun).add(key).add(value);
  }
}