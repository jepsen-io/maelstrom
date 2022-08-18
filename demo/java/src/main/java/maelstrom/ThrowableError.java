package maelstrom;

// A Throwable wrapper for a Maelstrom Error body. We throw exceptions of this type anywhere in request handlers, and have the mainloop catch them and send them back as replies to the client.
public class ThrowableError extends RuntimeException {
    public final Error error;

    public ThrowableError(Error error) {
        super(error.text);
        this.error = error;
    }

    public String toString() {
        return "(throwable-error " + error + ")";
    }
}