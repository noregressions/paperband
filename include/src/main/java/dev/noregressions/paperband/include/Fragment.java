package dev.noregressions.paperband.include;

import java.util.Optional;

/**
 * A piece of content fetched by a {@link ContentProvider}, ready to be handed
 * to a {@link FragmentProcessor}.
 *
 * @param content          the fragment text, exactly as it should appear (already
 *                         dedented, marker lines stripped, etc.)
 * @param mediaType        IANA media type (best-effort), e.g. {@code text/x-java},
 *                         {@code text/markdown}, {@code text/html}, {@code text/plain}
 * @param inferredLanguage hint for code-block highlighting if the processor is
 *                         {@code as code}; e.g. {@code java}, {@code python}, {@code yaml}.
 *                         May be empty when the provider can't determine it.
 * @param origin           human-readable source description used in error messages
 *                         (e.g. {@code samples/sb40/pom.xml#java-version}). May be empty.
 */
public record Fragment(
        String content,
        String mediaType,
        Optional<String> inferredLanguage,
        Optional<String> origin) {

    public Fragment {
        if (content == null) throw new IllegalArgumentException("content must not be null");
        if (mediaType == null || mediaType.isBlank())
            throw new IllegalArgumentException("mediaType must not be blank");
        if (inferredLanguage == null) inferredLanguage = Optional.empty();
        if (origin == null) origin = Optional.empty();
    }

    /**
     * Convenience: build a Fragment with no inferred language or origin.
     * @param content the fragment text
     * @param mediaType the media type
     * @return a new fragment
     */
    public static Fragment of(String content, String mediaType) {
        return new Fragment(content, mediaType, Optional.empty(), Optional.empty());
    }
}
