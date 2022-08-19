package maelstrom.echo;

import com.eclipsesource.json.Json;

import maelstrom.Node1;

public class EchoServer {
  public void run() {
    final Node1 node = new Node1();
    node.on("echo", (req) -> {
      node.reply(req, Json.object()
          .add("type", "echo_ok")
          .add("echo", req.body.getString("echo", null)));
    });
    node.log("Starting up!");
    node.main();
  }
}
