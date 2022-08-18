package maelstrom;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("echo")
public class Echo extends Body {
    public final String echo;

    @JsonCreator
    public Echo(
            @JsonProperty("msg_id") long msg_id,
            @JsonProperty("echo") String echo) {
        super(msg_id);
        this.echo = echo;
    }

    public String toString() {
        return "(echo " + msg_id + " " + echo + ")";
    }
}