package dev.noregressions.paperband.block;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One fenced block, handed to a {@link BlockRenderer}.
 *
 * <p>Everything a block template gets in its Pebble model, plus the two things
 * only code can use: where the card lives (so a renderer can resolve the
 * block's own includes relative to it) and the renderer's slice of the
 * {@code vars} cascade.
 *
 * @param type    the fence's language tag, exactly as written — a renderer
 *                claiming several tags can branch on which one it got
 * @param content the verbatim block text, unescaped, newlines intact
 * @param classes extra classes from the info line ({@code ```plantuml {.wide}}),
 *                to carry onto the output; never null, may be empty
 * @param id      an explicit {@code {#id}} attribute, or null
 * @param vars    the card's resolved cascade; never null
 * @param config  {@code vars.<rendererName>} as a map, or empty when the book
 *                configures nothing — the per-renderer settings block, and it
 *                cascades per folder and per card like every other var
 * @param bookRoot the book's home directory — where {@code paperband.yaml}
 *                lives — or null when there isn't one. The base for a config
 *                value that names a file, so a book-wide setting can point at
 *                one path rather than a path per card, and the same base other
 *                yaml-declared paths (a cover image, a stylesheet) resolve
 *                against
 * @param source  the card file this block came from, or null when the content
 *                has no file behind it. Useful as a base directory and, more
 *                importantly, as the thing to name in an error message
 */
public record BlockRequest(
        String type,
        String content,
        List<String> classes,
        String id,
        Map<String, Object> vars,
        Map<String, Object> config,
        Path bookRoot,
        Path source
) {

    public BlockRequest {
        classes = classes == null ? List.of() : List.copyOf(classes);
        // Not Map.copyOf: a yaml key written with no value is a null in the
        // cascade, and Map.copyOf rejects those. A book should not be able to
        // fail a build by leaving a var blank.
        vars = unmodifiable(vars);
        config = unmodifiable(config);
    }

    private static Map<String, Object> unmodifiable(Map<String, Object> m) {
        return m == null || m.isEmpty()
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(m));
    }

    /**
     * A {@link #config()} value as a string, or {@code fallback} when unset.
     *
     * <p>Config arrives from YAML, so a value may be any scalar the parser
     * produced; every renderer wants the same "read a setting, else the
     * default" line, and writing it once here keeps that from being seven
     * subtly different null checks.
     *
     * @param key      the setting name
     * @param fallback the value when the key is absent, null, or blank
     * @return the configured value, trimmed, or the fallback
     */
    public String setting(String key, String fallback) {
        Object v = config.get(key);
        if (v == null) return fallback;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? fallback : s;
    }

    /**
     * A {@link #config()} value that names a file, resolved to a real path.
     *
     * <p>Tried against the book root first and the card's own directory
     * second, so a book-wide setting can name one path from the root while a
     * card can still keep something beside itself. An absolute path is taken
     * as given.
     *
     * <p>A setting that names a file which isn't there is an error, not an
     * empty Optional: silently ignoring it would mean a book whose diagrams
     * quietly lost their styling after someone moved a directory. The message
     * names every place that was looked, so every renderer gets the same
     * decent error for free.
     *
     * @param key the setting name
     * @return the file, or empty when the key is unset
     * @throws BlockRenderException when the key is set and names nothing
     */
    public Optional<Path> file(String key) {
        String named = setting(key, null);
        if (named == null) return Optional.empty();
        List<Path> tried = new java.util.ArrayList<>();
        Path direct = Path.of(named);
        if (direct.isAbsolute()) {
            if (Files.isRegularFile(direct)) return Optional.of(direct);
            tried.add(direct);
        } else {
            for (Path base : new Path[] {bookRoot, source == null ? null : source.getParent()}) {
                if (base == null) continue;
                Path candidate = base.resolve(named);
                if (Files.isRegularFile(candidate)) return Optional.of(candidate);
                tried.add(candidate);
            }
        }
        throw new BlockRenderException(key + ": no file at " + named
                + (tried.isEmpty() ? "" : " — looked in " + tried));
    }

    /**
     * A boolean {@link #config()} value — yaml truthiness, matching the rest
     * of the pipeline.
     *
     * @param key      the setting name
     * @param fallback the value when the key is absent
     * @return the configured flag, or the fallback
     */
    public boolean flag(String key, boolean fallback) {
        Object v = config.get(key);
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(java.util.Locale.ROOT);
        if (s.equals("true") || s.equals("yes") || s.equals("1")) return true;
        if (s.equals("false") || s.equals("no") || s.equals("0")) return false;
        return fallback;
    }
}
