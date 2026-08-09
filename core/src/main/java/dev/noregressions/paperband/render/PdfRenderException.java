package dev.noregressions.paperband.render;

/** Thrown by {@link HtmlToPdfRenderer#render} when a render fails. */
public class PdfRenderException extends RuntimeException {

    public PdfRenderException(String message) {
        super(message);
    }

    public PdfRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
