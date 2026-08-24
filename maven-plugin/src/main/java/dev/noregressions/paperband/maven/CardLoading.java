package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.cards.CardLoader;
import dev.noregressions.paperband.cards.CardParseException;
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
     * <p>A card's id comes from its filename unless its frontmatter sets one,
     * so a book that names every scenario's file {@code TRACE.md} gives them
     * all the id {@code TRACE}. That id is an identity everywhere downstream:
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
        message.append("\n\nAn id defaults to the card's filename. Give each card its own "
                + "`id:` in frontmatter (e.g. id: s01-trace).");
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
}
