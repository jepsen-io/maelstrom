package maelstrom;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("init_ok")
public class InitOk extends Reply {
    @JsonCreator
    public InitOk(@JsonProperty("in_reply_to") long in_reply_to) {
        super(in_reply_to);
    }

    public InitOk(Init init) {
        this(init.msg_id);
    }

    public String toString() {
        return "(init_ok " + msg_id + " reply to " + in_reply_to + ")";
    }
}