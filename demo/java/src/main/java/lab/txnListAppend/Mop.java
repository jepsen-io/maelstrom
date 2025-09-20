package lab.txnListAppend;

import lab.IJson;
import com.eclipsesource.json.JsonArray;

// A Mop is a micro-operation performed in a transaction.
// It's written in JSON as a triple [fun, key, value].
public interface Mop extends IJson {
    public Long key();

    public String fun();

    public Object value();

    // Parses a JSON object as either an Append or a Read.
    public static Mop from(JsonArray a) {
        final String fun = a.get(0).asString();
        switch (fun) {
            case "append":
                return AppendMop.from(a);
            case "r":
                return ReadMop.from(a);
            default:
                throw new IllegalArgumentException("Unknown mop fun: " + fun);
        }
    }
}
