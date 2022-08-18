package maelstrom;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.TimeZone;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.exc.StreamWriteException;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.ObjectMapper;

// This class provides common support functions for writing Maelstrom nodes
public class Node {
    // How we serialize and deserialize JSON
    private final ObjectMapper jsonMapper = new ObjectMapper();

    // Our local node ID.
    public String nodeId;
    // All node IDs
    public List<String> nodeIds;
    // A map of request RPC types (e.g. Echo) to Consumers which should be invoked
    // when those messages arrive.
    public final Map<Class<? extends Body>, Consumer<? extends Message<? extends Body>>> requestHandlers = new HashMap<Class<? extends Body>, Consumer<? extends Message<? extends Body>>>();

    public Node() {
    }

    // Registers a request handler for the given type of message.
    public Node on(Class<? extends Body> type, Consumer<? extends Message<? extends Body>> handler) {
        requestHandlers.put(type, handler);
        return this;
    }

    // Log a message to stderr.
    public void log(String message) {
        TimeZone tz = TimeZone.getDefault();
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm");
        df.setTimeZone(tz);
        System.err.println(df.format(new Date()) + " " + message);
        System.err.flush();
    }

    // Send a message to stdout
    public void send(final Message<Body> message) {
        try {
            String text = jsonMapper.writeValueAsString(message);
            log("Sending " + text);
            System.out.println(text);
            System.out.flush();
        } catch (StreamWriteException e) {
            log("Error writing to STDOUT");
            e.printStackTrace();
            System.exit(2);
        } catch (DatabindException e) {
            log("Databind error:");
            e.printStackTrace();
            System.exit(2);
        } catch (IOException e) {
            log("IO error:");
            e.printStackTrace();
            System.exit(2);
        }
    }

    // Reply to a specific request message with a response Body.
    public void reply(Message<? extends Body> request, Body body) {
        send(new Message<Body>(nodeId, request.src, body));
    }

    // Handle an init message, setting up our state.
    public void handleInit(final Message<Init> request) {
        this.nodeId = request.body.node_id;
        this.nodeIds = request.body.node_ids;
    }

    // Handle a message by looking up a request handler by the type of the message's
    // body, and calling it with the message.
    public void handleRequest(Message<? extends Body> request) {
        final Body body = request.body;
        final Class<? extends Body> type = body.getClass();
        // You and I both know that this request handler takes e.g. Message<Echo>, and
        // that our message is also a Message<Echo>, but because of the dynamic nature
        // of the requestHandlers map and Java's covariance/contravariance rules, I
        // can't figure out how to convince the type system of this statically. Maybe
        // someone clever could figure this out!
        @SuppressWarnings("unchecked")
        Consumer<Message<? extends Body>> handler = (Consumer<Message<? extends Body>>) requestHandlers.get(type);
        if (handler == null) {
            // You don't have to register a custom Init handler.
            if (body instanceof Init) {
                return;
            }
            Error.notSupported(body, "Don't know how to handle a request of type " + type.getName());
        }
        handler.accept(request);
    }

    // Handles a parsed message from STDIN
    @SuppressWarnings("unchecked")
    public void handleMessage(Message<? extends Body> message) {
        final Body body = message.body;
        log("Handling message " + message);

        try {
            // Init messages are special: we always handle them ourselves in addition to
            // invoking any registered callback.
            if (body instanceof Init) {
                handleInit((Message<Init>) message);
                handleRequest(message);
                reply(message, new InitOk((Init) message.body));
            } else {
                handleRequest(message);
            }
        } catch (ThrowableError e) {
            // Send a message back to the client
            log(e.error.toString());
            reply(message, e.error);
        } catch (Exception e) {
            // Send a generic crash error
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String text = "Unexpected exception handling " +
            message + ": " + e + "\n" + sw;
            log(text);
            reply(message, new Error(message.body, 13, text));
        }
    }

    // The mainloop. Consumes lines of JSON from STDIN. Invoke this once the node is
    // configured to begin handling requests.
    public void main() {
        final Scanner scanner = new Scanner(System.in);
        String line;
        try {
            while (true) {
                line = scanner.nextLine();
                try {
                    // jsonMapper.readValue is going to return an unparameterized Message type
                    @SuppressWarnings("unchecked")
                    Message<? extends Body> message = ((Message<? extends Body>) jsonMapper.readValue(line,
                            Message.class));
                    handleMessage(message);
                } catch (JsonProcessingException e) {
                    log("Exception parsing JSON " + line);
                    e.printStackTrace();
                    System.exit(1);
                }
            }
        } finally {
            scanner.close();
        }
    }
}
