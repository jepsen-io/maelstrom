package maelstrom;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property="type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Init.class),
    @JsonSubTypes.Type(value = Echo.class),
    @JsonSubTypes.Type(value = Error.class)
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class Body {
    public static long nextMessageId = 0;
    public Long msg_id;

    synchronized public static long newMessageId() {
        final long id = nextMessageId;
        nextMessageId++;
        return id;
    }

    public Body() {
        this.msg_id = null;
    }

    public Body(long msg_id) {
        this.msg_id = msg_id;
    }
}