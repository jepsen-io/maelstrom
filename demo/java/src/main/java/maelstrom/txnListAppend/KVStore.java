package maelstrom.txnListAppend;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import maelstrom.Error;
import maelstrom.Node3;

// A KVStore represents a Maelstrom KV service.
public class KVStore {
  final Node3 node;
  final String service;

  public KVStore(Node3 node, String service) {
    this.node = node;
    this.service = service;
  }

  // Reads the given key
  public CompletableFuture<JsonValue> read(String k) {
    return node.rpc(service,
        Json.object()
            .add("type", "read")
            .add("key", k))
        .thenApply((res) -> {
          return res.get("value");
        });
  }

  // Reads the given key, returning a default if not found
  public CompletableFuture<JsonValue> read(String k, JsonValue defaultValue) {
    return read(k).exceptionally((e) -> {
      // Unwrap completionexceptions
      if (e instanceof CompletionException) {
        e = e.getCause();
      }
      if (e instanceof Error) {
        final Error err = (Error) e;
        if (err.code == 20) {
          return defaultValue;
        }
      }
      throw new RuntimeException(e);
    });
  }

  // Reads the given key, retrying not-found errors
  public CompletableFuture<JsonValue> readUntilFound(String k) {
    return read(k).exceptionally((e) -> {
      if (e instanceof CompletionException) {
        e = e.getCause();
      }
      if (e instanceof Error) {
        final Error err = (Error) e;
        if (err.code == 20) {
          return readUntilFound(k).join();
        }
      }
      throw new RuntimeException(e);
    });
  }

  // Writes the given key
  public CompletableFuture<JsonObject> write(String k, JsonValue v) {
    return node.rpc(service, Json.object()
        .add("type", "write")
        .add("key", k)
        .add("value", v));
  }

  // Compare-and-sets the given key from `from` to `to`. Creates key if it doesn't
  // exist.
  public CompletableFuture<JsonObject> cas(String k, JsonValue from, JsonValue to) {
    return node.rpc(service, Json.object()
        .add("type", "cas")
        .add("key", k)
        .add("from", from)
        .add("to", to)
        .add("create_if_not_exists", true));
  }
}
