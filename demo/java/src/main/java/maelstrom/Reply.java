package maelstrom;

public abstract class Reply extends Body {
    public final long in_reply_to;

    public Reply(long in_reply_to) {
        super();
        this.in_reply_to = in_reply_to;
    }
}