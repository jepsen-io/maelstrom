package lab;

import com.eclipsesource.json.Json;

public class GCounterServer {
    CRDTServer<GCounter> server = new CRDTServer<GCounter>(new GCounter());

    public void run() {
        // When we get an add message, add that element to the CRDT
        server.node.on("add", (req) -> {
            GCounter c2 = server.crdt;
            server.crdt = c2.add(server.node.nodeId, req.body.getLong("delta", -1));
            server.node.reply(req, Json.object().add("type", "add_ok"));
        });

        server.run();
    }
}