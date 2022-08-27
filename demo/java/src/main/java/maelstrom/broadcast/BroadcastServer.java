package maelstrom.broadcast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import maelstrom.JsonUtil;
import maelstrom.Node3;

public class BroadcastServer {
    public final Node3 node = new Node3();
    // The nodes we're directly adjacent to
    public List<String> neighbors = new ArrayList<String>();
    // All messages we know about
    public Set<Long> messages = new HashSet<Long>();

    // Sends a message to a neighbor and keep retrying if it times out or fails
    public void broadcastToNeighbor(String neighbor, JsonObject message) {
        node.rpc(neighbor, message)
                .orTimeout(1000, TimeUnit.MILLISECONDS)
                .exceptionally((t) -> {
                    node.log("Retrying broadcast of " + message + " to " + neighbor);
                    broadcastToNeighbor(neighbor, message);
                    return null;
                });
    }

    public void run() {
        // When we get a topology message, record our neighbors
        node.on("topology", (req) -> {
            final JsonArray neighbors = req.body
                    .get("topology").asObject()
                    .get(node.nodeId).asArray();
            for (JsonValue neighbor : neighbors) {
                this.neighbors.add(neighbor.asString());
            }
            node.reply(req, Json.object().add("type", "topology_ok"));
        });

        // When we get a read, respond with our set of messages
        node.on("read", (req) -> {
            node.reply(req, Json.object()
                    .add("type", "read_ok")
                    .add("messages", JsonUtil.longsToJson(messages)));
        });

        // And when we get an add, add it to the local set and broadcast it out
        node.on("broadcast", (req) -> {
            final Long message = req.body.getLong("message", -1);
            if (!messages.contains(message)) {
                messages.add(message);
                for (String neighbor : neighbors) {
                    broadcastToNeighbor(neighbor,
                            Json.object()
                                    .add("type", "broadcast")
                                    .add("message", message));
                }
            }

            node.reply(req, Json.object().add("type", "broadcast_ok"));
        });

        node.log("Starting up!");
        node.main();
    }
}