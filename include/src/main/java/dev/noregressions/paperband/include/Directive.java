package dev.noregressions.paperband.include;

import java.util.Map;
import java.util.Optional;

/**
 * Parsed include directive: the result of scanning {@code {{#include ...}}}
 * out of raw markdown.
 *
 * @param reference   the first whitespace-separated token after {@code #include}.
 *                    May carry a scheme prefix (e.g. {@code git://repo:path:tag});
 *                    short-form {@code path:tag} defaults to the file provider.
 * @param attributes  {@code key=value} attributes parsed from the directive.
 *                    Both providers and processors read from this single map.
 * @param returnType  the value of {@code as <type>} if present, else empty
 *                    (in which case the processor is inferred from the
 *                    fragment's media type).
 * @param sourceStart byte offset of the opening {{ in the source markdown
 * @param sourceEnd   byte offset just past the closing }}
 */
public record Directive(
        String reference,
        Map<String, String> attributes,
        Optional<String> returnType,
        int sourceStart,
        int sourceEnd) {

    public Directive {
        if (reference == null || reference.isBlank())
            throw new IllegalArgumentException("directive reference must not be blank");
        if (attributes == null) attributes = Map.of();
        if (returnType == null) returnType = Optional.empty();
    }
}
