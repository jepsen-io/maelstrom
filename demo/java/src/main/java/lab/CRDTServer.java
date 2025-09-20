package lab;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.eclipsesource.json.Json;

public class CRDTServer<C extends CRDT<C>> {
    public Node node;
    public C crdt;

    public CRDTServer(C crdt) {
        this.node = new Node();
        this.crdt = crdt;
        init();
    }

    // Set up handlers
    public void init() {
        // On gossip, merge
        node.on("gossip", (req) -> {
            crdt = (C) crdt.merge(req.body.get("value"));
        });

        // When Maelstrom asks, give them our local state
        node.on("read", (req) -> {
            node.reply(req,
                    Json.object()
                            .add("type", "read_ok")
                            .add("value", crdt.read()));
        });
    }

    // When run, we set up a thread to periodically gossip our state
    public void run() {
        final ScheduledExecutorService executor = new ScheduledThreadPoolExecutor(1);
        executor.scheduleAtFixedRate(() -> {
            // Send state to other nodes
            for (final String n : node.nodeIds) {
                if (!n.equals(node.nodeId)) {
                    node.send(n, Json.object()
                    .add("type", "gossip")
                    .add("value", crdt.toJson()));
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
        node.run();
    }
}