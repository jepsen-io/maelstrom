package lab;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonValue;

import io.lacuna.bifurcan.IMap;
import io.lacuna.bifurcan.Map;

public class GCounter implements CRDT<GCounter> {
    public IMap<String, Long> state;

    public GCounter() {
        this.state = new Map<String, Long>().forked();
    }

    public GCounter(IMap<String, Long> state) {
        this.state = state;
    }

    public JsonValue read() {
        long sum = 0;
        for (Long v : state.values()) {
            sum += v;
        }
        return Json.value(sum);
    }

    public JsonValue toJson() {
        return JsonUtil.toJson(state, JsonUtil.stringToJson, JsonUtil.longToJson);
    }

    public GCounter merge(JsonValue other) {
        return new GCounter(
            state.merge(JsonUtil.toMap(
                other.asArray(),
                JsonUtil.asString,
                JsonUtil.asLong),
                (x, y) -> { return Math.max(x, y); }));
    }

    public GCounter add(String node, long x) {
        return new GCounter(state.put(node, x, (a, b) -> { return a + b; }));
    }
}