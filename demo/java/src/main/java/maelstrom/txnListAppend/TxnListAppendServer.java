package maelstrom.txnListAppend;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;

import io.lacuna.bifurcan.IMap;
import io.lacuna.bifurcan.List;
import io.lacuna.bifurcan.Map;
import io.lacuna.bifurcan.Set;
import maelstrom.Error;
import maelstrom.JsonUtil;
import maelstrom.Node3;
import maelstrom.txnListAppend.State.StateTxn;

public class TxnListAppendServer {
  public final Node3 node = new Node3();
  public final KVStore thunkStore = new KVStore(node, "lin-kv");
  public final KVStore rootStore = new KVStore(node, "lin-kv");
  // Where do we store the root?
  public final String rootKey = "root";

  // What's the next thunk number?
  public static final AtomicLong nextThunkNumber = new AtomicLong(0);

  // A mutable cache of thunk IDs to thunks
  public static final ConcurrentHashMap<String, Thunk> thunkCache = new ConcurrentHashMap<String, Thunk>();

  // A cache for what we think the current root is
  public final AtomicReference<Root> rootCache = new AtomicReference<>(new Root());

  // Generate a new thunk ID for a node.
  public final String newThunkId() {
    return node.nodeId + "-" + nextThunkNumber.getAndIncrement();
  }

  // Fetch a thunk, either from cache or storage
  public CompletableFuture<Thunk> loadThunk(String id) {
    // Try cache
    if (thunkCache.containsKey(id)) {
      node.log("cache hit " + id);
      final CompletableFuture<Thunk> f = new CompletableFuture<Thunk>();
      f.complete(thunkCache.get(id));
      return f;
    }

    // Fall back to KV store
    return thunkStore.readUntilFound(id).thenApply((json) -> {
      final Thunk thunk = new Thunk(id, JsonUtil.toBifurcanList(json.asArray(), (element) -> {
        return element.asLong();
      }));
      // Update cache
      thunkCache.put(id, thunk);
      return thunk;
    });
  }

  // Write a thunk to storage and cache
  public CompletableFuture<JsonObject> saveThunk(Thunk thunk) {
    thunkCache.put(thunk.id(), thunk);
    return thunkStore.write(thunk.id(), JsonUtil.longsToJson(thunk.value()));
  }

  // Takes a Root and a set of keys. Returns a (partial) State constructed by
  // looking up those keys' corresponding thunks in the root.
  public State loadPartialState(Root root, Set<Long> keys) {
    final IMap<Long, String> rootMap = root.map();
    final ConcurrentHashMap<Long, List<Long>> stateMap = new ConcurrentHashMap<>();
    // Fire off reads for each key with a thunk
    keys.stream().filter((k) -> {
      return rootMap.contains(k);
    }).map((k) -> {
      final String thunkId = rootMap.get(k, null);
      return loadThunk(thunkId).thenAccept((thunk) -> {
        stateMap.put(k, thunk.value());
      });
    }).toList().stream().forEach((future) -> {
      // And block on each read
      future.join();
    });
    return new State(Map.from(stateMap));
  }

  // Takes a State and a set of keys. Generates a fresh thunk ID for each of those
  // keys, and writes the corresponding value to the thunk store. Returns a
  // (partial) Root which maps those keys to their newly-created thunk IDs.
  public Root savePartialState(State state, Set<Long> keys) {
    final IMap<Long, List<Long>> stateMap = state.map();
    final ConcurrentHashMap<Long, String> rootMap = new ConcurrentHashMap<>();
    // Fire off writes for each key
    keys.stream().map(k -> {
      final String thunkId = newThunkId();
      final Thunk thunk = new Thunk(thunkId, stateMap.get(k, null));
      rootMap.put(k, thunkId);
      return saveThunk(thunk);
    }).toList().stream().forEach((future) -> {
      // Block on each write
      future.join();
    });
    return new Root(Map.from(rootMap));
  }

  public void run() {
    node.on("txn", (req) -> {
      // Parse txn
      final Txn txn = new Txn(req.body.get("txn").asArray());
      // Use cached root
      final Root root = rootCache.get();
      // Fetch thunks and construct a partial state
      final State state = loadPartialState(root, txn.readSet());
      // Apply txn
      final StateTxn stateTxn2 = state.apply(txn);
      final State state2 = stateTxn2.state();
      final Txn txn2 = stateTxn2.txn();
      // Save thunks and merge into root
      final Root root2 = root.merge(savePartialState(state2, txn.writeSet()));
      // Update root
      try {
        rootStore.cas(rootKey, root.toJson(), root2.toJson()).join();
        rootCache.compareAndSet(root, root2);
        // And confirm txn
        node.reply(req, Json.object()
        .add("type", "txn_ok")
        .add("txn", txn2.toJson()));
      } catch (CompletionException e) {
        if (e.getCause() instanceof Error) {
          final Error err = (Error)  e.getCause();
          if (err.code == 22) {
            // Cas failed because of mismatching precondition. Reload root!
            rootStore.read(rootKey, Json.array()).thenAccept((rootJson) -> {
              node.log("Refreshed root");
              rootCache.set(Root.from(rootJson));
            });
            throw Error.txnConflict("root altered");
          } else {
            // Some other Maelstrom error
            throw Error.abort("Unexpected error updating root: " + err);
          }
        } else {
          throw e;
        }
      }
    });

    node.log("Starting up!");
    node.main();
  }
}