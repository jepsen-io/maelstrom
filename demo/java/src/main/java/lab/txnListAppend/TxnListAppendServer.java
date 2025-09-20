package lab.txnListAppend;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;

import io.lacuna.bifurcan.IList;
import io.lacuna.bifurcan.ISortedMap;
import io.lacuna.bifurcan.Map;
import io.lacuna.bifurcan.SortedMap;
import lab.JsonUtil;
import lab.Node;
import lab.Error;
import lab.txnListAppend.State.StateTxn;

public class TxnListAppendServer {
    public Node node = new Node();
    public final KVStore rootStore = new KVStore(node, "lin-kv");
    public final KVStore thunkStore = new KVStore(node, "lww-kv");
    public final ConcurrentHashMap<String, Thunk> thunkCache = new ConcurrentHashMap<>();
    public final String rootKey = "r";
    public final AtomicLong nextFlakeId = new AtomicLong(0);
    public final AtomicReference<ISortedMap<Long, String>> rootCache = new AtomicReference<>(new SortedMap<>());

    // Generate a new globally unique flake ID string
    public String flakeId() {
        long id = nextFlakeId.getAndIncrement();
        return node.nodeId + "." + id;
    }

    // Reads a thunk from storage
    public CompletableFuture<Thunk> readThunk(String id) {
        // Try cache first
        final Thunk cached = thunkCache.get(id);
        if (cached != null) {
            CompletableFuture<Thunk> f = new CompletableFuture<>();
            f.complete(cached);
            return f;
        }

        // Read from storage
        return rootStore.readUntilFound(id).thenApply((json) -> {
            node.log("Read thunk " + id + " = " + json);
            final Thunk thunk = new Thunk(id, JsonUtil.toList(json.asArray(), JsonUtil.asLong));
            // Cache
            thunkCache.put(thunk.id(), thunk);
            return thunk;
        });
    }

    // Writes a thunk to storage
    public CompletableFuture<JsonObject> writeThunk(Thunk thunk) {
        node.log("Write thunk " + thunk.id() + " = " + thunk.value());
        return rootStore.write(thunk.id(),
         JsonUtil.toJson(thunk.value(), JsonUtil.longToJson)).thenApply((r) -> {
            // Cache as side effect
            thunkCache.put(thunk.id(), thunk);
            return r;
         });
    }

    // Takes a root and a transaction. Loads thunks, constructing a partial State
    // required to evaluate the txn.
    public State readState(ISortedMap<Long, String> root, Txn txn) {
        ConcurrentHashMap<Long, IList<Long>> state = new ConcurrentHashMap<>();
        txn.readSet().stream().filter((k) -> {
            // For every read key having a thunk...
            return root.contains(k);
        }).map((k) -> {
            // Get that thunk
            final String thunkId = root.get(k, null);
            return readThunk(thunkId).thenAccept((thunk) -> {
                // And deliver thunks to the stateMap
                state.put(k, thunk.value());
            });
        }).toList().stream().forEach((future) -> {
            future.join();
        });
        node.log("Read partial state " + state);
        return new State(Map.from(state));
    }

    // Takes a transaction and a new state. Saves thunks for each key written in
    // that transaction,
    // returning a map of keys to thunk IDs written.
    public SortedMap<Long, String> writeState(Txn txn, State state2) {
        node.log("Write partial state " + state2);
        final ConcurrentHashMap<Long, String> root = new ConcurrentHashMap<>();
        txn.writeSet().stream().map(k -> {
            final String thunkId = flakeId();
            final Thunk thunk = new Thunk(thunkId, state2.map().get(k, null));
            root.put(k, thunkId);
            return writeThunk(thunk);
        }).toList().stream().forEach((future) -> {
            // Await all
            future.join();
        });
        node.log("New partial root " + root);
        return SortedMap.from(root);
    }

    // Reads current root from storage
    public SortedMap<Long, String> readRoot() {
        JsonArray a = rootStore.read(rootKey, Json.array()).join().asArray();
        node.log("Read root array " + a);
        SortedMap<Long, String> map = JsonUtil.toSortedMap(a, JsonUtil.asLong, JsonUtil.asString);
        node.log("Which parsed to " + map);
        return map;
    }

    // Saves root to storage
    public void writeRoot(ISortedMap<Long, String> from, ISortedMap<Long, String> to) {
        node.log("cas root " + from + " -> " + to);
        JsonArray a1 = JsonUtil.toJson(from, JsonUtil.longToJson, JsonUtil.stringToJson);
        JsonArray a2 = JsonUtil.toJson(to, JsonUtil.longToJson, JsonUtil.stringToJson);
        try {
            rootStore.cas(rootKey, a1, a2).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof Error) {
                final Error err = (Error) e.getCause();
                if (err.code == 22) {
                    // CAS mismatch
                    throw Error.txnConflict("root altered");
                } else {
                    throw Error.abort("Unexpected error saving root: " + err);
                }
            } else {
                throw e;
            }
        }
    }

    // Run a transaction using cached root, returning resulting transaction
    public Txn txn(Txn txn1) {
        ISortedMap<Long, String> root1 = rootCache.get();
        State state1 = readState(root1, txn1);

        // Apply txn
        StateTxn st = state1.apply(txn1);
        Txn txn2 = st.txn();
        State state2 = st.state();

        // Save thunks, updating root
        final ISortedMap<Long, String> root2 = root1.union(writeState(txn2, state2));

        // Optimization: read-only queries can proceed without altering the root at all
        // Enabling this turns a Strong Serializable system to just Serializable,
        // since we might operate on stale state.
        if (txn1.writeSet().size() == 0) {
            // return txn2;
        }

        // Save root
        try {
            writeRoot(root1, root2);
            rootCache.set(root2);
        } catch (Error e) {
            if (e.code == 30) {
                // Transaction conflict; reload root and retry
                rootCache.set(readRoot());
                return txn(txn1);
            } else {
                throw e;
            }
        }

        return txn2;
    }

    // Mainloop
    public void run() {
        node.on("txn", (req) -> {
            // Parse
            Txn txn1 = Txn.from(req.body.get("txn").asArray());
            node.log("");
            node.log("Txn: " + req.body.get("txn").asArray());

            // Evaluate
            Txn txn2 = txn(txn1);

            // Reply
            node.reply(req, Json.object()
                    .add("type", "txn_ok")
                    .add("txn", txn2.toJson()));
        });
        node.run();
    }
}