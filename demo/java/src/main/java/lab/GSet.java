package lab;

import com.eclipsesource.json.JsonValue;

import io.lacuna.bifurcan.ISortedSet;
import io.lacuna.bifurcan.SortedSet;

public class GSet implements CRDT<GSet> {
    public ISortedSet<Long> set;

    public GSet() {
        this.set = new SortedSet<Long>().forked();
    }

    public GSet(ISortedSet<Long> set) {
        this.set = set;
    }

    public JsonValue read() {
        return JsonUtil.toJson(set);
    }

    public JsonValue toJson() {
        return JsonUtil.toJson(set);
    }

    public GSet merge(JsonValue other) {
        ISortedSet<Long> set2 = set.union(JsonUtil.toSet(other.asArray(), JsonUtil.asLong));
        return new GSet(set2);
    }

    public GSet add(long x) {
        return new GSet(set.add(x));
    }
}
