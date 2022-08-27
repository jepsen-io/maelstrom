package maelstrom;

import java.util.function.Function;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import com.eclipsesource.json.JsonObject.Member;

import io.lacuna.bifurcan.IMap;
import io.lacuna.bifurcan.List;
import io.lacuna.bifurcan.Map;

// A few basic coercions for JSON
public class JsonUtil {
  // Functions to coerce JsonValues to longs, strings, etc.
  public static Function<JsonValue, Long>   asLong   = (j) -> { return j.asLong(); };
  public static Function<JsonValue, String> asString = (j) -> { return j.asString(); };

  // Convert an iterable of longs to a JsonArray
  public static JsonArray longsToJson(Iterable<Long> iterable) {
    final JsonArray a = Json.array();
    for (long e : iterable) {
      a.add(e);
    }
    return a;
  }

  // Convert an iterable of T to a JsonArray via a function.
  public static <T> JsonArray toJson(Iterable<T> iterable, Function<T, JsonValue> f) {
    final JsonArray a = Json.array();
    for (T e : iterable) {
      a.add(f.apply(e));
    }
    return a;
  }

  // Convert an iterable of IJsons to a JsonArray
  public static JsonArray toJson(Iterable<IJson> iterable) {
    final JsonArray a = Json.array();
    for (IJson e : iterable) {
      a.add(e.toJson());
    }
    return a;
  }

  // Convert a json array to a bifurcan list, transforming elements using the given function.
  public static <T> List<T> toBifurcanList(JsonArray a, Function<JsonValue, T> f) {
    List<T> list = new List<T>().forked();
    for (JsonValue element : a) {
      list = list.addLast(f.apply(element));
    }
    return list.forked();
  }

  // Convert a json object to a bifurcan map, transforming keys and values using
  // the given functions.
  public static <K, V> IMap<K, V> toBifurcanMap(JsonObject o, Function<String, K> keyFn, Function<JsonValue, V> valFn) {
    IMap<K, V> m = new Map<K, V>().linear();
    for (Member pair : o) {
      m = m.put(keyFn.apply(pair.getName()),
                valFn.apply(pair.getValue()));
    }
    return m.forked();
  }
}
