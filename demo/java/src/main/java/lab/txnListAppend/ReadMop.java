package lab.txnListAppend;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonValue;

import io.lacuna.bifurcan.IList;
import lab.JsonUtil;

// Represents an append micro-operation
public record ReadMop(String fun, Long key, IList<Long> value) implements Mop {
    // Parse from JSON
    public static ReadMop from(JsonArray a) {
        final String f = a.get(0).asString();
        final Long k = a.get(1).asLong();
        final JsonValue v = a.get(2);
        if (v.isNull()) {
            // No value for this read
            return new ReadMop(f, k, null);
        } else {
            return new ReadMop(f, k, JsonUtil.toList(a, JsonUtil.asLong));
        }
    }

    @Override
    public String fun() {
        return fun;
    }

    @Override
    public Long key() {
        return key;
    }

    @Override
    public IList<Long> value() {
        return value;
    }

    // Serialize to JSON
    @Override
    public JsonValue toJson() {
        return Json.array()
                .add(Json.value(fun))
                .add(Json.value(key))
                .add(JsonUtil.toJson(value));
    }
}