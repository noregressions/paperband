package dev.noregressions.paperband.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * The "is this file a card?" rules, shared by every way a book's contents get
 * selected — {@link BookWalker}'s directory walk and {@link BookPlan}'s
 * pattern match. Both must agree on what counts as a card, or the same file
 * would appear in a walked book and vanish from a planned one.
 */
final class CardFiles {

    static final String CONFIG_FILENAME = "paperband.yaml";

    private CardFiles() {}

    /**
     * Is {@code p} a card file, for a book that <em>discovered</em> it?
     *
     * <p>{@code .md} is always a card except {@code README.md}, which is
     * conventionally a repo readme. {@code .yaml}/{@code .yml} count only
     * when the book opted in by declaring a {@code cardSchema:}, and never
     * for the {@code paperband.yaml} config files themselves.
     *
     * @param p                the candidate file
     * @param acceptYamlCards  whether the book root declares a {@code cardSchema:}
     * @return true when the file should be loaded as a card
     */
    /**
     * Markdown files that describe a folder rather than being a card in it.
     *
     * <p>{@code README.md} is documentation about the directory; {@code
     * _section.md} is a section's own introduction, rendered onto its landing
     * page (see {@code SiteMojo.sectionIntros}). Sweeping either into the book
     * as a card is wrong, and for {@code _section.md} it is visibly wrong — the
     * prose would appear both above the card list and as an entry in it.
     */
    private static final java.util.Set<String> INTRO_FILENAMES =
            java.util.Set.of("readme.md", "_section.md");

    static boolean isCard(Path p, boolean acceptYamlCards) {
        return isCard(p, acceptYamlCards, false);
    }

    /**
     * Is {@code p} a card file?
     *
     * <p>The {@code README.md} rule is a <em>discovery</em> heuristic: walking a
     * tree, a readme is documentation about the repo rather than a card in the
     * book, and sweeping it up is almost always wrong. A glob is the opposite
     * situation — {@code scenarios/&#42;/README.md} names those files and nothing
     * else, so refusing them means a pattern that quietly matches nothing, with
     * no way to tell that from an empty directory.
     *
     * @param p                the candidate file
     * @param acceptYamlCards  whether the book root declares a {@code cardSchema:}
     * @param namedExplicitly  true when a pattern asked for this file by name,
     *                         which overrides the readme heuristic
     * @return true when the file should be loaded as a card
     */
    static boolean isCard(Path p, boolean acceptYamlCards, boolean namedExplicitly) {
        String name = p.getFileName().toString();
        if (name.endsWith(".md")) {
            return namedExplicitly || !INTRO_FILENAMES.contains(name.toLowerCase(Locale.ROOT));
        }
        if (acceptYamlCards && (name.endsWith(".yaml") || name.endsWith(".yml"))) {
            String lower = name.toLowerCase(Locale.ROOT);
            return !lower.equals("paperband.yaml") && !lower.equals("paperband.yml");
        }
        return false;
    }

    /**
     * Is {@code p} an HTML card <em>candidate</em>? Deliberately not part of
     * {@link #isCard}: whether an {@code .html} file actually becomes a card
     * is decided by <em>where it was found</em>, not by its extension alone —
     * books sit inside projects full of generated HTML (a previous site
     * build, emitted debug pages), so the callers gate it: {@link BookWalker}
     * accepts HTML cards only when walking a {@code content/}-scoped root
     * (authored by definition), and {@link BookPlan} only when the include
     * pattern itself ends in {@code .html} (deliberate by spelling).
     *
     * @param p the candidate file
     * @return true when the file is an {@code .html} file
     */
    static boolean isHtmlCard(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".html");
    }

    /**
     * Find the book root the same way {@code ConfigLoader} does — the topmost
     * {@code paperband.yaml} walking parents up from {@code start} — and
     * report whether it declares a {@code cardSchema:}. That's the opt-in for
     * treating {@code .yaml} files as cards.
     *
     * @param start the walk's starting file or directory
     * @return true when the book root declares a {@code cardSchema:}
     */
    static boolean declaresCardSchema(Path start) {
        Path dir = start.toAbsolutePath();
        if (!Files.isDirectory(dir)) dir = dir.getParent();
        Path topmost = null;
        while (dir != null) {
            Path config = dir.resolve(CONFIG_FILENAME);
            if (Files.isRegularFile(config)) topmost = config;
            dir = dir.getParent();
        }
        if (topmost == null) return false;
        try {
            return readYaml(new Yaml(), topmost).containsKey("cardSchema");
        } catch (ConfigParseException e) {
            // Leave the real error to ConfigLoader, which reports it properly.
            return false;
        }
    }

    /**
     * Read a {@code paperband.yaml} as a mapping.
     *
     * @param yaml the (non-thread-safe) snakeyaml instance to parse with
     * @param file the file to read
     * @return the parsed mapping, empty when the document is blank
     * @throws ConfigParseException when unreadable or not a mapping at top level
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> readYaml(Yaml yaml, Path file) {
        try (Reader r = Files.newBufferedReader(file)) {
            Object data = yaml.load(r);
            if (data == null) return Map.of();
            if (data instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            throw new ConfigParseException(
                    file + ": top level must be a YAML mapping");
        } catch (IOException e) {
            throw new ConfigParseException("Failed to read " + file, e);
        }
    }
}
