package dev.claudev.provider;

/** No-payload success marker for port methods that only need to signal "done." */
public record Ack() {
    public static final Ack INSTANCE = new Ack();
}
