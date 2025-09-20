package lab;

import com.eclipsesource.json.Json;

public class GSetServer {
    CRDTServer<GSet> server = new CRDTServer<GSet>(new GSet());

    public void run() {
        // When we get an add message, add that element to the CRDT
        server.node.on("add", (req) -> {
            GSet set2 = server.crdt;
            set2 = set2.add(req.body.getLong("element", -1));
            server.crdt = set2;
            server.node.reply(req, Json.object().add("type", "add_ok"));
        });

        server.run();
    }
}