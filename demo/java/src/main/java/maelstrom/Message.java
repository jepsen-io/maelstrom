package maelstrom;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

// A message contains an optional ID, a source and destination, and a JsonObject body.
public class Message implements IJson {
    public final Long id;
    public final String src;
    public final String dest;
    public final JsonObject body;

    public Message(Long id, String src, String dest, JsonObject body) {
        this.id = id;
        this.src = src;
        this.dest = dest;
        this.body = body;
    }

    public Message(String src, String dest, JsonObject body) {
        this(null, src, dest, body);
    }

    public Message(Long id, String src, String dest, IJson body) {
        this(id, src, dest, body.toJson().asObject());
    }

    public Message(String src, String dest, IJson body) {
        this(null, src, dest, body);
    }

    // Parse a JsonObject as a message.
    public Message(JsonValue j) {
        final JsonObject o = j.asObject();
        long id = o.getLong("id", -1);
        if (id == -1) {
            this.id = null;
        } else {
            this.id = id;
        }
        this.src = o.getString("src", null);
        this.dest = o.getString("dest", null);
        this.body = o.get("body").asObject();
    }

    public String toString() {
        return "(msg " + src + " " + dest + " " + body + ")";
    }

    @Override
    public JsonValue toJson() {
        final JsonObject o = Json.object();
        if (id != null) {
            o.add("id", id);
        }
        return o.add("src", src)
                .add("dest", dest)
                .add("body", body);
    }
}
