package lab;

import java.util.function.Function;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonValue;

import io.lacuna.bifurcan.IEntry;
import io.lacuna.bifurcan.IMap;
import io.lacuna.bifurcan.IList;
import io.lacuna.bifurcan.List;
import io.lacuna.bifurcan.Map;
import io.lacuna.bifurcan.Set;
import io.lacuna.bifurcan.SortedMap;

public class JsonUtil {
    // Functional coercers
    public static Function<JsonValue, Long> asLong = (j) -> {
        return j.asLong();
    };
    public static Function<JsonValue, String> asString = (j) -> {
        return j.asString();
    };
    public static Function<Long, JsonValue> longToJson = (x) -> {
        return Json.value(x);
    };
    public static Function<String, JsonValue> stringToJson = (x) -> {
        return Json.value(x);
    };

    // Turns a list of longs to a JsonArray
    public static JsonArray toJson(final Iterable<Long> longs) {
        final JsonArray a = Json.array();
        for (final long x : longs) {
            a.add(x);
        }
        return a;
    }

    // Turns a Bifurcan IMap into a k,v,k,v JSON array, using key and value
    // conversion functions
    public static <K, V> JsonArray toJson(IMap<K, V> m, Function<K, JsonValue> keyFun, Function<V, JsonValue> valFun) {
        JsonArray a = Json.array();
        for (IEntry<K, V> e : m) {
            a.add(keyFun.apply(e.key()));
            a.add(valFun.apply(e.value()));
        }
        return a;
    }

    // Turns a Bifurcan IList into a JSON array, using a value conversion function
    public static <V> JsonArray toJson(IList<V> l, Function<V, JsonValue> f) {
        JsonArray a = Json.array();
        for (V v : l) {
            a.add(f.apply(v));
        }
        return a;
    }

    // Turn a JsonArray into a Bifurcan List
    public static <T> List<T> toList(JsonArray a, Function<JsonValue, T> f) {
        List<T> list = new List<T>().linear();
        for (JsonValue v : a) {
            list.addLast(f.apply(v));
        }
        return list.forked();
    }

    // Turn a JsonArray into a Bifurcan Set
    public static <T> Set<T> toSet(JsonArray a, Function<JsonValue, T> f) {
        Set<T> set = new Set<T>().linear();
        for (JsonValue v : a) {
            set.add(f.apply(v));
        }
        return set.forked();
    }

    // Turn a k,v,k,v Json Array into a Bifurcan Map
    public static <K, V> IMap<K, V> toMap(JsonArray a, Function<JsonValue, K> keyFun, Function<JsonValue, V> valFun) {
        Map<K, V> m = new Map<K, V>().linear();
        for (int i = 0; i < a.size(); i += 2) {
            m.put(keyFun.apply(a.get(i)), valFun.apply(a.get(i + 1)));
        }
        return m.forked();
    }

    // Turn a k,v,k,v Json Array into a Bifurcan SortredMap
    public static <K, V> SortedMap<K, V> toSortedMap(JsonArray a, Function<JsonValue, K> keyFun, Function<JsonValue, V> valFun) {
        SortedMap<K, V> m = new SortedMap<K, V>().linear();
        for (int i = 0; i < a.size(); i += 2) {
            m.put(keyFun.apply(a.get(i)), valFun.apply(a.get(i + 1)));
        }
        return m.forked();
    }
}
