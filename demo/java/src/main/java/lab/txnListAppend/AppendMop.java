package lab.txnListAppend;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonValue;

// Represents an append micro-operation
public record AppendMop(String fun, Long key, Long value) implements Mop {
    // Parse from JSON
    public static AppendMop from(JsonArray a) {
        return new AppendMop(a.get(0).asString(),
                a.get(1).asLong(),
                a.get(2).asLong());
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
    public Long value() {
        return value;
    }

    // Serialize to JSON
    @Override
    public JsonValue toJson() {
        return Json.array()
                .add(Json.value(fun))
                .add(Json.value(key))
                .add(Json.value(value));
    }
}