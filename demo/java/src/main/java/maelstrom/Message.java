package maelstrom;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

// A message contains a source and destination, and a JsonObject body.
public class Message implements IJson {
  public final String src;
  public final String dest;
  public final JsonObject body;

  public Message(String src, String dest, JsonObject body) {
    this.src = src;
    this.dest = dest;
    this.body = body;
  }

  public Message(String src, String dest, IJson body) {
    this(src, dest, body.toJson().asObject());
  }

  // Parse a JsonObject as a message.
  public Message(JsonValue j) {
    final JsonObject o = j.asObject();
    this.src  = o.getString("src", null);
    this.dest = o.getString("dest", null);
    this.body = o.get("body").asObject();
  }

  public String toString() {
    return "(msg " + src + " " + dest + " " + body + ")";
  }

  @Override
  public JsonValue toJson() {
    return Json.object()
        .add("src", src)
        .add("dest", dest)
        .add("body", body);
  }
}
