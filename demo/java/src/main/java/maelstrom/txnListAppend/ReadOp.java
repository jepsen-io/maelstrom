package maelstrom.txnListAppend;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonValue;

import io.lacuna.bifurcan.List;
import maelstrom.JsonUtil;

// A read operation is a triple of a function, a key, and a list of longs.
public record ReadOp(String fun, Long key, List<Long> value) implements IOp {
  public static ReadOp from(JsonArray json) {
    final JsonValue jv = json.get(2);
    List<Long> value = null;
    if (! jv.isNull()) {
      value = JsonUtil.toBifurcanList(jv.asArray(), JsonUtil.asLong);
    }
    return new ReadOp(json.get(0).asString(),
        json.get(1).asLong(),
        value);
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
    final JsonArray a = Json.array().add(fun).add(key);
    if (value == null) {
      a.add(Json.NULL);
    } else {
      a.add(JsonUtil.longsToJson(value));
    }
    return a;
  };
}