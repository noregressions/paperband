package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.cards.BlockTemplates;
import dev.noregressions.paperband.cards.CardLoader;
import dev.noregressions.paperband.cards.CardParseException;
import dev.noregressions.paperband.cards.ContentPolicy;
import dev.noregressions.paperband.cards.MarkdownPreprocessor;
import dev.noregressions.paperband.cards.YamlCardTranspiler;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.CardSchema;

import org.apache.maven.plugin.MojoFailureException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading one card file into a {@link Card}, shared by every goal that loads
 * cards ({@code build}, {@code site}, {@code structure}, {@code publish}).
 */
final class CardLoading {

    private CardLoading() {}

    /**
     * Fail on cards sharing an id.
     *
     * <p>An undeclared id is derived from the card's path, so it's unique per
     * file — a clash therefore means two cards <em>declare</em> the same one.
     * That id is an identity everywhere downstream:
     * the PDF's {@code #card-<id>} named destination, the static site's
     * {@code cards/<id>.html} page, and every cross-link to either. A
     * collision therefore doesn't merge cards, it loses them — the site writes
     * one page and overwrites it for each duplicate, and the PDF's anchors
     * collapse so tooling reports one span covering several cards.
     *
     * <p>Loudly, then: the fix is a one-line {@code id:} in the frontmatter,
     * and the alternative is a book quietly missing content.
     *
     * @param cards    the assembled cards, in book order
     * @param bookRoot the root paths are reported relative to
     * @throws MojoFailureException listing each duplicated id and its files
     */
    static void requireUniqueIds(List<Card> cards, Path bookRoot) throws MojoFailureException {
        Map<String, List<Path>> byId = new LinkedHashMap<>();
        for (Card card : cards) {
            byId.computeIfAbsent(card.id(), k -> new ArrayList<>()).add(card.source());
        }
        List<Map.Entry<String, List<Path>>> clashes = byId.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .toList();
        if (clashes.isEmpty()) return;

        StringBuilder message = new StringBuilder("Duplicate card ids — a card id names the "
                + "PDF's anchor and the site's cards/<id>.html page, so cards sharing one "
                + "overwrite each other rather than coexisting:");
        for (Map.Entry<String, List<Path>> clash : clashes) {
            message.append("\n  '").append(clash.getKey()).append("' claimed by ")
                    .append(clash.getValue().size()).append(" cards:");
            for (Path source : clash.getValue()) {
                message.append("\n    ").append(relativise(bookRoot, source));
            }
        }
        message.append("\n\nAn undeclared id is derived from the card's path, which is unique "
                + "per file — so a clash means these cards declare the same `id:` in their "
                + "frontmatter. Change one, or drop it and take the derived id.");
        throw new MojoFailureException(message.toString());
    }

    /** Book-relative path where possible, for a message someone can act on. */
    private static String relativise(Path bookRoot, Path source) {
        if (bookRoot == null || source == null) return String.valueOf(source);
        try {
            return bookRoot.toAbsolutePath().normalize()
                    .relativize(source.toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException e) {
            return source.toString();
        }
    }

    /**
     * Read {@code cardFile} and run the pre-flexmark preprocessing pass
     * (fragment resolution + vars/conditionals — see
     * {@code PebbleIncludePreprocessor} in {@code include}), then parse the
     * result into a {@link Card}.
     *
     * <p>A {@code .yaml}/{@code .yml} card is transpiled to markdown first,
     * driven by the book's {@code cardSchema:} — before the preprocessor, so
     * includes, vars and conditionals behave identically for both card formats.
     *
     * <p>Bypasses {@link CardLoader#load(Path)} because {@code preprocessor}
     * varies per card (it binds that card's vars at construction) while
     * {@code load} knows only about a single configured instance; calling
     * {@code parse} directly keeps one {@link CardLoader} — and its flexmark
     * engine — shared across the whole book.
     *
     * @param cardLoader   the shared loader
     * @param preprocessor that card's preprocessor, or null to skip preprocessing
     * @param cardFile     the card to read
     * @param cardSchema   the book's yaml-card schema, or null when it declares none
     * @return the parsed card
     */
    static Card load(CardLoader cardLoader,
                     MarkdownPreprocessor preprocessor,
                     Path cardFile,
                     CardSchema cardSchema) {
        String source;
        try {
            source = Files.readString(cardFile, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new CardParseException("Failed to read " + cardFile, e);
        }
        if (YamlCardTranspiler.isYamlCard(cardFile)) {
            source = new YamlCardTranspiler().transpile(cardFile, source, cardSchema);
        }
        if (preprocessor != null) {
            source = preprocessor.process(source, cardFile);
        }
        return cardLoader.parse(cardFile, source);
    }

    /**
     * Load one card under the content policy its {@code vars} cascade
     * declares ({@code vars: { contentPolicy: allow | clean | strict }},
     * default {@code clean}) — set per card, since a folder can override the
     * book-wide value through the cascade. CLEAN-mode removals go to the
     * build log as warnings, each naming the card and what went.
     *
     * @param cardLoader   the shared loader
     * @param preprocessor pre-flexmark pass, or null
     * @param cardFile     the card file
     * @param cardSchema   yaml-card schema, or null
     * @param vars         the card's resolved vars, for {@code contentPolicy}
     * @param log          where removals are reported
     * @return the loaded card
     * @throws CardParseException on an unknown policy value, naming the valid ones
     */
    static Card load(CardLoader cardLoader,
                     MarkdownPreprocessor preprocessor,
                     Path cardFile,
                     CardSchema cardSchema,
                     Map<String, Object> vars,
                     org.apache.maven.plugin.logging.Log log) {
        return load(cardLoader, preprocessor, cardFile, cardSchema, vars, log, null);
    }

    /**
     * {@link #load(CardLoader, MarkdownPreprocessor, Path, CardSchema, Map,
     * org.apache.maven.plugin.logging.Log)} with a book-aware block-template
     * resolver, so {@code ```type} blocks render through the theme's and the
     * book's {@code blocks/<type>.html} templates as well as the bundled
     * ones. Null keeps the bundled-only default.
     */
    static Card load(CardLoader cardLoader,
                     MarkdownPreprocessor preprocessor,
                     Path cardFile,
                     CardSchema cardSchema,
                     Map<String, Object> vars,
                     org.apache.maven.plugin.logging.Log log,
                     BlockTemplates blockTemplates) {
        ContentPolicy policy;
        try {
            policy = ContentPolicy.parse(vars == null ? null : vars.get("contentPolicy"));
        } catch (IllegalArgumentException e) {
            throw new CardParseException(cardFile + ": " + e.getMessage());
        }
        cardLoader.setContentPolicy(policy, log == null ? null : log::warn);
        cardLoader.setBlockTemplates(blockTemplates, vars);
        return load(cardLoader, preprocessor, cardFile, cardSchema);
    }
}
