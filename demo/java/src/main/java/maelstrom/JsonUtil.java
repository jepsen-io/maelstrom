package maelstrom;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;

// A few basic coercions for JSON
public class JsonUtil {
    // Convert an iterable of longs to a JsonArray
    public static JsonArray longArray(Iterable<Long> iterable) {
        final JsonArray a = Json.array();
        for (long e : iterable) {
            a.add(e);
        }
        return a;
    }
}