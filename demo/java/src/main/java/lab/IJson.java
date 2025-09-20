package lab;

import com.eclipsesource.json.JsonValue;

// Stuff that can be coerced to a JsonValue
public interface IJson {
    public JsonValue toJson();
}
