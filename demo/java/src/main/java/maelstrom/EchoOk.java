package maelstrom;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("echo_ok")
public class EchoOk extends Reply {
    public final String echo;

    @JsonCreator
    public EchoOk(
        @JsonProperty("in_reply_to") long in_reply_to,
        @JsonProperty("echo") String echo) {
        super(in_reply_to);
        this.echo = echo;
    }

    public EchoOk(Echo request, String echo) {
        this(request.msg_id, echo);
    }

    public String toString() {
        return "(echo_ok " + msg_id + " reply to " + in_reply_to + " echo " + echo + ")";
    }
}