package lab.txnListAppend;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonValue;

import io.lacuna.bifurcan.IList;
import io.lacuna.bifurcan.ISet;
import io.lacuna.bifurcan.Set;
import lab.IJson;
import lab.JsonUtil;

// A transaction is a list of [f, k, v] micro-operations.
public record Txn(IList<Mop> mops) implements IJson {
    // Parse from JSON
    public static Txn from(JsonArray a) {
        return new Txn(JsonUtil.toList(a, (mop) -> {
            return Mop.from(mop.asArray());
        }));
    }

    // Serialize to JSON
    @Override
    public JsonValue toJson() {
        return JsonUtil.toJson(mops, (mop) -> {
            return mop.toJson();
        });
    }

    // All keys read or written in a txn
    public ISet<Long> readSet() {
        ISet<Long> s = new Set<Long>().linear();
        for (final Mop m : mops) {
            s = s.add(m.key());
        }
        return s.forked();
    }

    // All keys written by a transaction
    public ISet<Long> writeSet() {
        ISet<Long> s = new Set<Long>().linear();
        for (final Mop m : mops) {
            if (m instanceof AppendMop) {
                s = s.add(m.key());
            }
        }
        return s.forked();
    }
}
