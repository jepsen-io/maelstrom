package maelstrom.echo;

import com.eclipsesource.json.Json;

import maelstrom.Node;

public class EchoServer {
    public void run() {
        final Node node = new Node();
        node.on("echo", (req) -> {
            node.reply(req, Json.object()
            .add("type", "echo_ok")
            .add("echo", req.body.getString("echo", null)));
        });
        node.log("Starting up!");
        node.main();
    }
}