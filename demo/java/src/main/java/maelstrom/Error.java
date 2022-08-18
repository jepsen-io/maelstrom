package maelstrom;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

// The body of a Maelstrom error message
@JsonTypeName("error")
public class Error extends Reply {
    public final long code;
    public final String text;

    @JsonCreator
    public Error(
        @JsonProperty("in_reply_to") long in_reply_to,
        @JsonProperty("code") long code,
        @JsonProperty("text") String text) {
        super(in_reply_to);
        this.code = code;
        this.text = text;
    }

    // Helper for constructing an error in reply to a specific request body.
    public Error(Body request, long code, String text) {
        this(request.msg_id, code, text);
    }

    // And utility functions to throw specific kinds of errors, following https://github.com/jepsen-io/maelstrom/blob/main/doc/protocol.md#errors
    public static void notSupported(Body request, String text) {
        throw new ThrowableError(new Error(request, 10, text));
    }
    public static void crash(Body request, String text) {
        throw new ThrowableError(new Error(request, 13, text));
    }
    

    public String toString() {
        return "(error " + msg_id + " reply to " + in_reply_to + " code " + code + " " + text + ")";
    }
}