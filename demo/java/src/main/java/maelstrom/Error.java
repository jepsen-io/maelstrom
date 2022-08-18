package maelstrom;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonValue;

// The body of a Maelstrom error message, as a throwable exception.
public class Error extends RuntimeException implements IJson {
    public final long code;
    public final String text;

    public Error(long code, String text) {
        this.code = code;
        this.text = text;
    }

    // Utility functions to construct specific kinds of errors, following https://github.com/jepsen-io/maelstrom/blob/main/doc/protocol.md#errors
    public static Error timeout(String text) {
        return new Error(0, text);
    }
    public static Error notSupported(String text) {
        return new Error(10, text);
    }
    public static Error temporarilyUnavailable(String text) {
        return new Error(11, text);
    }
    public static Error malformedRequest(String text) {
        return new Error(12, text);
    }
    public static Error crash(String text) {
        return new Error(13, text);
    }
    public static Error abort(String text) {
        return new Error(14, text);
    }
    public static Error keyDoesNotExist(String text) {
        return new Error(20, text);
    }
    public static Error keyAlreadyExists(String text) {
        return new Error(21, text);
    }
    public static Error preconditionFailed(String text) {
        return new Error(22, text);
    }
    public static Error txnConflict(String text) {
        return new Error(30, text);
    }

    public JsonValue toJson() {
        return Json.object()
        .add("type", "error")
        .add("code", code)
        .add("text", text);
    }

    public String toString() {
        return toJson().toString();
    }
}