package maelstrom;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;

// A message containing a body of type B
@JsonAutoDetect(fieldVisibility = Visibility.ANY)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Message<B extends Body> {
    public final Long id;
    public final String src;
    public final String dest;
    public final B body;

    @JsonCreator
    public Message(
            @JsonProperty("id") Long id,
            @JsonProperty("src") String src,
            @JsonProperty("dest") String dest,
            @JsonProperty("body") B body) {
        this.id = id;
        this.src = src;
        this.dest = dest;
        this.body = body;
    }

    public Message(String src, String dest, B body) {
        this(null, src, dest, body);
    }

    public String toString() {
        return "(msg " + src + " " + dest + " " + body + ")";
    }
}