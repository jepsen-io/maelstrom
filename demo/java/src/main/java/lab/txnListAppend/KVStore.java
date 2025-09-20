package lab.txnListAppend;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import lab.Node;
import lab.Error;

// A handle to Maelstrom's built-in key-value services
public class KVStore {
    final Node node;
    final String service;

    public KVStore(Node node, String service) {
        this.node = node;
        this.service = service;
    }

    // Reads a key, returning a future of its value, not handling errors
    public CompletableFuture<JsonValue> read(String k) {
        return node.rpc(service,
                Json.object()
                        .add("type", "read")
                        .add("key", k))
                .thenApply((res) -> {
                    return res.get("value");
                });
    }

    // Reads a key, returning a default if not found
    public CompletableFuture<JsonValue> read(String k, JsonValue notFound) {
        return read(k)
                .exceptionally((e) -> {
                    // Unwrap CompletionExceptions
                    if (e instanceof CompletionException) {
                        e = e.getCause();
                    }
                    if (e instanceof Error) {
                        final Error err = (Error) e;
                        if (err.code == 20) {
                            return notFound;
                        } else {
                            throw err;
                        }
                    }
                    // Something else?
                    throw new RuntimeException(e);
                });
    }

    // Reads forever until successful
    public CompletableFuture<JsonValue> readUntilFound(String k) {
        return read(k).exceptionally((e) -> {
            // Unwrap CompletionException
            if (e instanceof CompletionException) {
                e = e.getCause();
            }
            if (e instanceof Error) {
                final Error err = (Error) e;
                if (err.code == 20) {
                    // Retry not-found
                    return readUntilFound(k).join();
                }
            }
            throw new RuntimeException(e);
        });
    }

    // Writes key
    public CompletableFuture<JsonObject> write(String k, JsonValue v) {
        return node.rpc(service, Json.object()
                .add("type", "write")
                .add("key", k)
                .add("value", v));
    }

    // Compares and sets a key from `from` to `to`. Creates key if it doesn't exist.
    public CompletableFuture<JsonObject> cas(String k, JsonValue from, JsonValue to) {
        return node.rpc(service, Json.object()
                .add("type", "cas")
                .add("key", k)
                .add("from", from)
                .add("to", to)
                .add("create_if_not_exists", true));
    }
}