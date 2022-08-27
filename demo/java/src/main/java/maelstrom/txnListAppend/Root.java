package maelstrom.txnListAppend;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonValue;

import io.lacuna.bifurcan.IEntry;
import io.lacuna.bifurcan.IMap;
import io.lacuna.bifurcan.SortedMap;
import maelstrom.IJson;

// A Root is a map of keys to thunk IDs.
public record Root(IMap<Long, String> map) implements IJson {
  // An empty root
  public Root() {
    this(new SortedMap<Long, String>().forked());
  }

  // Inflate a root from a JSON array
  public static Root from(JsonValue json) {
    final JsonArray a = json.asArray();
    IMap<Long, String> m = new SortedMap<Long, String>().linear();
    for (int i = 0; i < a.size(); i = i + 2) {
      m = m.put(a.get(i).asLong(), a.get(i + 1).asString());
    }
    return new Root(m.forked());
  }

  // We serialize roots as a list of flat [k v k v] pairs.
  public JsonValue toJson() {
    JsonArray a = Json.array();
    for (IEntry<Long, String> pair: map) {
      a = a.add(pair.key()).add(pair.value());
    }
    return a;
  }

  // Merge two roots together
  public Root merge(Root other) {
    return new Root(map.merge(other.map(), (a, b) -> b));
  }
}