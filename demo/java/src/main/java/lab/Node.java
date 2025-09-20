package lab;

import java.util.Date;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Scanner;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.util.List;
import java.util.Map;

public class Node {
    public String nodeId = null;
    public List<String> nodeIds = new ArrayList<String>();
    public long nextMessageId = 0;
    public Map<String, Consumer<Message>> handlers = new HashMap<String, Consumer<Message>>();
    public Map<Long, CompletableFuture<JsonObject>> rpcs = new HashMap<Long, CompletableFuture<JsonObject>>();
    public ExecutorService executor = Executors.newCachedThreadPool();

    public Node() {
    }

    // Mainloop
    public void run() {
        final Scanner scanner = new Scanner(System.in);

        String line;
        try {
            while (true) {
                line = scanner.nextLine();
                Message message = Message.parse(line);
                executor.execute(() -> handleMessage(message));
            }
        } catch (Throwable t) {
            System.err.println("Fatal error! " + t);
            t.printStackTrace();
            System.exit(1);
        } finally {
            scanner.close();
        }
    }

    // Handle a single message
    public void handleMessage(Message req) {
        try {
            final JsonObject body = req.body;
            if (body.getString("type", null).equals("init")) {
                // On init, write down our node ID and others.
                nodeId = body.getString("node_id", null);
                for (JsonValue id : body.get("node_ids").asArray()) {
                    this.nodeIds.add(id.asString());
                }
                reply(req, Json.object().add("type", "init_ok"));
            } else if (body.getLong("in_reply_to", -1) != -1) {
                // This is a reply to an RPC we sent
                handleReply(req);
            } else {
                // This is a top-level request
                handleRequest(req);
            }
        } catch (Error e) {
            // Our custom errors
            log(e.toString());
            reply(req, e.toJson().asObject());
        } catch (Exception e) {
            // General errors
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String text = "Unexpected exception handling " +
            req + " : " + e + "\n" + sw;
            log(text);
            reply(req, Error.crash(text).toJson().asObject());
        }
    }

    // Handle an inbound request
    public void handleRequest(Message req) {
            // Look up handler
            final String type = req.body.getString("type", null);
            final Consumer<Message> handler = handlers.get(type);
            if (handler == null) {
                throw new RuntimeException("No handler for " + type);
            }
            handler.accept(req);
    }

    // Handle an RPC reply
    public void handleReply(Message req) {
        final JsonObject body = req.body;
        final long in_reply_to = body.getLong("in_reply_to", -1);
        final CompletableFuture<JsonObject> f = rpcs.get(in_reply_to);
        if (f == null) {
            // Handler already triggered, or not our RPC
            return;
        }
        rpcs.remove(in_reply_to);
        if (body.getString("type", null).equals("error")) {
            // When we get an error, futures complete exceptionally
            f.completeExceptionally(
                    new Error(
                            body.getLong("code", -1),
                            body.getString("text", null)));
        } else {
            // Normal completion
            f.complete(body);
        }
    }

    // Send an RPC request. Returns a CompletableFuture of a response body.
    public CompletableFuture<JsonObject> rpc(String dest, JsonObject body) {
        body = ensureMsgId(body);
        long msgId = body.getLong("msg_id", -1);
        CompletableFuture<JsonObject> f = new CompletableFuture<JsonObject>();
        rpcs.put(msgId, f);
        send(dest, body);
        return f;
    }

    // Reply to an inbound message
    public void reply(Message request, JsonObject responseBody) {
        long msg_id = request.body.getLong("msg_id", -1);
        JsonObject body = Json.object().merge(responseBody).set("in_reply_to", msg_id);
        send(new Message(this.nodeId, request.src, body));
    }

    // Send a message to a particular node.
    public void send(String dest, JsonObject body) {
        send(new Message(nodeId, dest, body));
    }

    // Send a message to stdout
    public synchronized void send(Message message) {
        if (message.body.getLong("msg_id", -1) == -1) {
            message = new Message(message.src, message.dest, ensureMsgId(message.body));
        }
        System.out.println(message.toJson());
        System.out.flush();
    }

    // Ensures a body has a message ID
    public JsonObject ensureMsgId(JsonObject body) {
        if (body.getLong("msg_id", -1) == -1) {
            body = Json.object().merge(body).set("msg_id", messageId());
        }
        return body;
    }

    // Get a new message ID.
    public synchronized long messageId() {
        long id = nextMessageId;
        nextMessageId += 1;
        return id;
    }

    // Register a new handler for a message type
    public Node on(String type, Consumer<Message> handler) {
        handlers.put(type, handler);
        return this;
    }

    // Log a simple message
    public synchronized void log(String message) {
        TimeZone tz = TimeZone.getDefault();
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        df.setTimeZone(tz);
        System.err.println(df.format(new Date()) + " " + message);
        System.err.flush();
    }
}