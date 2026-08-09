package dev.noregressions.paperband.render;

import java.util.List;

/**
 * PDF document metadata. Any field may be {@code null} (or empty for keywords).
 * Renderers are expected to set what they can and ignore what they can't.
 */
public record PdfMetadata(String title, String author, String subject, List<String> keywords) {

    public PdfMetadata {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    public static PdfMetadata empty() {
        return new PdfMetadata(null, null, null, List.of());
    }

    public static PdfMetadata of(String title) {
        return new PdfMetadata(title, null, null, List.of());
    }
}
