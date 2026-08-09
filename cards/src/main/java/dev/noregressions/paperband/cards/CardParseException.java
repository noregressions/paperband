package dev.noregressions.paperband.cards;

/** Thrown when a markdown card cannot be parsed. */
public class CardParseException extends RuntimeException {

    public CardParseException(String message) {
        super(message);
    }

    public CardParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
