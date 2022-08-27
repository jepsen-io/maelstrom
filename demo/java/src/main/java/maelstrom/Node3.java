package maelstrom;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

// This class provides common support functions for writing Maelstrom nodes. It includes an asynchronous RPC facility, and uses an executor to launch handlers.
public class Node3 {
  // Our local node ID.
  public String nodeId = "uninitialized";

  // All node IDs
  public List<String> nodeIds = new ArrayList<String>();

  // A map of request RPC types (e.g. "echo") to Consumer<Message>s which should
  // be invoked when those messages arrive.
  public final Map<String, Consumer<Message>> requestHandlers = new HashMap<String, Consumer<Message>>();

  // A map of RPC request message IDs we've sent to CompletableFutures which will
  // receive the response bodies.
  public final Map<Long, CompletableFuture<JsonObject>> rpcs = new HashMap<Long, CompletableFuture<JsonObject>>();

  // Our next message ID to generate
  public long nextMessageId = 0;

  public ExecutorService executor = Executors.newCachedThreadPool();

  public Node3() {
  }

  // Registers a request handler for the given type of message.
  public Node3 on(String type, Consumer<Message> handler) {
    requestHandlers.put(type, handler);
    return this;
  }

  // Generate a new message ID
  public long newMessageId() {
    final long id = nextMessageId;
    nextMessageId++;
    return id;
  }

  // Log a message to stderr.
  public void log(String message) {
    TimeZone tz = TimeZone.getDefault();
    DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    df.setTimeZone(tz);
    System.err.println(df.format(new Date()) + " " + message);
    System.err.flush();
  }

  // Sending messages //////////////////////////////////////////////////////

  // Send a message to stdout
  public void send(final Message message) {
    log("Sending  " + message.toJson());
    System.out.println(message.toJson());
    System.out.flush();
  }

  // Send a message to a specific node. Automatically assigns a message ID if one
  // is not set.
  public void send(String dest, JsonObject body) {
    if (body.getLong("msg_id", -1) == -1) {
      body = Json.object().merge(body).set("msg_id", newMessageId());
    }
    send(new Message(nodeId, dest, body));
  }

  // Send an RPC request to another node. Returns a CompletableFuture which will
  // be delivered the response body when it arrives.
  public CompletableFuture<JsonObject> rpc(String dest, JsonObject request) {
    final CompletableFuture<JsonObject> f = new CompletableFuture<JsonObject>();
    final long id = newMessageId();
    rpcs.put(id, f);
    send(dest, Json.object().merge(request).set("msg_id", id));
    return f;
  }

  // Reply to a specific request message with a JsonObject body.
  public void reply(Message request, JsonObject body) {
    final Long msg_id = request.body.getLong("msg_id", -1);
    final JsonObject body2 = Json.object().merge(body).set("in_reply_to", msg_id);
    send(request.src, body2);
  }

  // Reply to a message with a Json-coercable object as the body.
  public void reply(Message request, IJson body) {
    reply(request, body.toJson().asObject());
  }

  // Handlers ////////////////////////////////////////////////////////////

  // Handle an init message, setting up our state.
  public void handleInit(Message request) {
    this.nodeId = request.body.getString("node_id", null);
    for (JsonValue id : request.body.get("node_ids").asArray()) {
      this.nodeIds.add(id.asString());
    }
    log("I am " + nodeId);
  }

  // Handle a reply to an RPC request we issued.
  public void handleReply(Message reply) {
    final JsonObject body = reply.body;
    final long in_reply_to = body.getLong("in_reply_to", -1);
    final CompletableFuture<JsonObject> f = rpcs.get(in_reply_to);
    if (f == null) {
      // Handler already triggered?
      return;
    }
    rpcs.remove(in_reply_to);
    if (body.getString("type", null).equals("error")) {
      // If we have an error, deliver an exception
      final long code = body.getLong("code", -1);
      final String text = body.getString("text", null);
      f.completeExceptionally(new Error(code, text));
    } else {
      // Normal completion
      f.complete(body);
    }
  }

  // Handle a message by looking up a request handler by the type of the message's
  // body, and calling it with the message.
  public void handleRequest(Message request) {
    final String type = request.body.getString("type", null);
    Consumer<Message> handler = requestHandlers.get(type);
    if (handler == null) {
      // You don't have to register a custom Init handler.
      if (type.equals("init")) {
        return;
      }
      throw Error.notSupported("Don't know how to handle a request of type " + type);
    }
    handler.accept(request);
  }

  // Handles a parsed message from STDIN
  public void handleMessage(Message message) {
    final JsonObject body = message.body;
    final String type = body.getString("type", null);
    final long in_reply_to = body.getLong("in_reply_to", -1);
    log("Handling " + message);

    try {
      // Init messages are special: we always handle them ourselves in addition to
      // invoking any registered callback.
      if (type.equals("init")) {
        handleInit(message);
        handleRequest(message);
        reply(message, Json.object().add("type", "init_ok"));
      } else if (in_reply_to != -1) {
        // A reply to an RPC we issued.
        handleReply(message);
      } else {
        // Dispatch based on message type.
        handleRequest(message);
      }
    } catch (Error e) {
      // Send a message back to the client
      log(e.toString());
      reply(message, e);
    } catch (Exception e) {
      // Send a generic crash error
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      e.printStackTrace(pw);
      String text = "Unexpected exception handling " +
          message + ": " + e + "\n" + sw;
      log(text);
      reply(message, Error.crash(text));
    }
  }

  // The mainloop. Consumes lines of JSON from STDIN. Invoke this once the node is
  // configured to begin handling requests.
  public void main() {
    final Scanner scanner = new Scanner(System.in);
    try {
      while (true) {
        final String line = scanner.nextLine();
        final Message message = new Message(Json.parse(line).asObject());
        executor.execute(() -> handleMessage(message));
      }
    } catch (Throwable e) {
      log("Fatal error! " + e);
      e.printStackTrace();
      System.exit(1);
    } finally {
      scanner.close();
    }
  }
}