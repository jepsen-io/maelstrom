package maelstrom;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.TimeZone;
import java.util.function.Consumer;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

// A minimal Maelstrom node, with a basic mainloop, support for init messages, and pluggable RPC request handlers
public class Node1 {
  // Our local node ID.
  public String nodeId = "uninitialized";

  // All node IDs
  public List<String> nodeIds = new ArrayList<String>();

  // A map of request RPC types (e.g. "echo") to Consumer<Message>s which should
  // be invoked when those messages arrive.
  public final Map<String, Consumer<Message>> requestHandlers = new HashMap<String, Consumer<Message>>();

  public Node1() {
  }

  // Registers a request handler for the given type of message.
  public Node1 on(String type, Consumer<Message> handler) {
    requestHandlers.put(type, handler);
    return this;
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

  // Send a message to a specific node.
  public void send(String dest, JsonObject body) {
    send(new Message(nodeId, dest, body));
  }

  // Reply to a specific request message with a JsonObject body.
  public void reply(Message request, JsonObject body) {
    final Long msg_id = request.body.getLong("msg_id", -1);
    final JsonObject body2 = Json.object().merge(body).set("in_reply_to", msg_id);
    send(request.src, body2);
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

  // Handle a message by looking up a request handler by the type of the message's
  // body, and calling it with the message.
  public void handleRequest(Message request) {
    final String type = request.body.getString("type", null);
    Consumer<Message> handler = requestHandlers.get(type);
    if (handler == null) {
      // You don't have to register a custom init handler.
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
    log("Handling " + message);

    // Init messages are special: we always handle them ourselves in addition to
    // invoking any registered callback.
    if (type.equals("init")) {
      handleInit(message);
      handleRequest(message);
      reply(message, Json.object().add("type", "init_ok"));
    } else {
      // Dispatch based on message type.
      handleRequest(message);
    }
  }

  // The mainloop. Consumes lines of JSON from STDIN. Invoke this once the node is
  // configured to begin handling requests.
  public void main() {
    final Scanner scanner = new Scanner(System.in);
    String line;
    Message message;
    try {
      while (true) {
        line = scanner.nextLine();
        message = new Message(Json.parse(line).asObject());
        handleMessage(message);
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