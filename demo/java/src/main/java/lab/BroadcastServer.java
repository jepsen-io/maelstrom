package lab;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

public class BroadcastServer {
    Node node = new Node();
    Set<Long> values = new HashSet<Long>();
    public List<String> neighbors = new ArrayList<String>();

    // Sends a body to a neighbor, retrying until it succeeds.
    public void gossipReliably(String dest, JsonObject body) {
        node.rpc(dest, body)
                .orTimeout(1000, TimeUnit.MILLISECONDS)
                .exceptionally((t) -> {
                    node.log("Retrying broadcast of" + body + " to " + dest);
                    gossipReliably(dest, body);
                    return null;
                });
    }

    public boolean handleBroadcast(Message req) {
        Long value = req.body.getLong("message", -1);
        boolean isNew = !values.contains(value);

        if (isNew) {
            values.add(value);

            // Send to neighbors
            for (final String n : neighbors) {
                if (!n.equals(req.src)) {
                    gossipReliably(n, Json.object()
                            .add("type", "broadcast")
                            .add("message", value));
                }
            }   
        }

        return isNew;
    }

    public void run() { 
        // When we get a topology message, we want to save our neighbors
        node.on("topology", (req) -> {
            for (JsonValue neighbor : req.body.get("topology").asObject().get(node.nodeId).asArray()) {
                this.neighbors.add(neighbor.asString());
            }
            node.reply(req, Json.object()
                    .add("type", "topology_ok"));
        });

        // When we get a broadcast message, save its value
        node.on("broadcast", (req) -> {
            handleBroadcast(req);
            node.reply(req, Json.object().add("type", "broadcast_ok"));
        });

        // When Maelstrom asks, give them the values we have
        node.on("read", (req) -> {
            node.reply(req,
                    Json.object()
                            .add("type", "read_ok")
                            .add("messages", JsonUtil.toJson(values)));
        });

        node.run();
    }
}