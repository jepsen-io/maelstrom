package maelstrom;

import com.eclipsesource.json.JsonValue;

// Support for coercing datatypes to and from JsonValues.
public interface IJson {
  // Coerce something to a JsonValue.
  public JsonValue toJson();
}