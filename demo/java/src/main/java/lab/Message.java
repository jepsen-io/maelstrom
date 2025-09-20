package lab;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

public class Message implements IJson {
    public final String src;
    public final String dest;
    public final JsonObject body;

    public Message(String src, String dest, JsonObject body) {
        this.src = src;
        this.dest = dest;
        this.body = body;
    }

    public static Message parse(String str) {
        final JsonObject o = Json.parse(str).asObject();
        return new Message(o.getString("src", null),
                o.getString("dest", null),
                o.get("body").asObject());

    }

    public JsonValue toJson() {
        return Json.object()
                .add("src", src)
                .add("dest", dest)
                .add("body", body);
    }

    public String toString() {
        return "(msg " + src + " " + dest + " " + body + ")";
    }
}