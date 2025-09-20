package lab;

import com.eclipsesource.json.Json;

public class EchoServer {
    public void run() {
        Node node = new Node();
        node.on("echo", (req) -> {
            node.log("Got echo request " + req);
            node.reply(req, Json.object()
            .add("type", "echo_ok")
            .add("echo", req.body.getString("echo", null)));
        });
        node.run();
    }
}