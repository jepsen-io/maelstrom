package maelstrom;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

@JsonTypeName("init")
public class Init extends Body {
    public final String node_id;
    public final List<String> node_ids;

    @JsonCreator
    public Init(
            @JsonProperty("msg_id") long msg_id,
            @JsonProperty("node_id") String node_id,
            @JsonProperty("node_ids") List<String> node_ids) {
        super(msg_id);
        this.node_id = node_id;
        this.node_ids = node_ids;
    }

    public String toString() {
        return "(init " + msg_id + " " + node_id + " " + node_ids + ")";
    }
}