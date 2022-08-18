package maelstrom;

import java.util.function.Consumer;

public class App {
    public static void main(String[] args) {
        final Node node = new Node();
        Consumer<Message<Echo>> echoHandler = (request) -> {
            Echo body = request.body;
            node.reply(request, new EchoOk(body, body.echo));
        };
        node.on(Echo.class, echoHandler);
        node.log("Starting up!");
        node.main();
    }
}