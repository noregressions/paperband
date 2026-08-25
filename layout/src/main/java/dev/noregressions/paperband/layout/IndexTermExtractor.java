package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automatic index terms for {@code index: auto} — the concordance the author
 * didn't have to write.
 *
 * <p>The question a back-of-book index answers is "where do I read about X",
 * so the terms worth printing are the ones <em>distinctive</em> to a card:
 * frequent in it, rare across the rest of the book. That's TF-IDF, computed
 * over Lucene-analysed tokens ({@link EnglishAnalyzer}: tokenisation,
 * stopwords, stemming — so "theme" and "themes" score as one term and print
 * as whichever surface form the card actually used most).
 *
 * <p>Two kinds of candidate, scored in one pool:
 * <ul>
 *   <li><b>Words</b> from body text and headings, stemmed. Heading words
 *       weigh {@value #HEADING_WEIGHT}× — in technical writing the heading
 *       is where a card says what it's about.</li>
 *   <li><b>Code identifiers</b> — the text of a short, single-token
 *       {@code <code>} span ({@code paperband.yaml}, {@code <margins>}),
 *       kept verbatim rather than analysed, weighted
 *       {@value #CODE_WEIGHT}×. In docs these are the index entries readers
 *       actually look up.</li>
 * </ul>
 *
 * <p>Guardrails, because a bad index entry costs more than a missing one:
 * a term must appear in at most a third of the cards (anything commoner is
 * the book's subject, not an index entry), at most {@value #MAX_PER_CARD}
 * terms per card win, terms shorter than {@value #MIN_LENGTH} characters or
 * purely numeric never qualify, and the book can veto any term by name via
 * {@code vars.indexStop}. Everything is deterministic: same book, same
 * index — ties break alphabetically.
 */
final class IndexTermExtractor {

    static final int MAX_PER_CARD = 5;
    static final int MIN_LENGTH = 3;
    static final double HEADING_WEIGHT = 3.0;
    static final double CODE_WEIGHT = 2.0;
    /** A term in more than this share of cards is too common to locate anything. */
    static final double MAX_DOC_SHARE = 1.0 / 3.0;

    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern CODE_SPAN =
            Pattern.compile("<code[^>]*>(.*?)</code>", Pattern.DOTALL);
    /** Fenced code blocks — excluded entirely: a Java example's {@code public}
     *  and {@code return} are frequent and distinctive, and index-worthless. */
    private static final Pattern PRE_BLOCK =
            Pattern.compile("<pre[^>]*>.*?</pre>", Pattern.DOTALL);
    private static final Pattern ALL_DIGITS = Pattern.compile("[0-9.,x\\-]+");

    private IndexTermExtractor() {}

    /**
     * The auto-selected terms per card id, in score order.
     *
     * @param cards the book's cards, in book order
     * @param stopTerms terms the book vetoed ({@code vars.indexStop}),
     *                  matched case-insensitively against the printed form
     */
    static Map<String, List<String>> extract(List<Card> cards, Set<String> stopTerms) {
        Set<String> stops = new HashSet<>();
        for (String s : stopTerms) stops.add(s.toLowerCase(Locale.ROOT));

        // Pass 1: per-card term statistics.
        List<CardTerms> perCard = new ArrayList<>(cards.size());
        Map<String, Integer> docFreq = new HashMap<>();
        for (Card card : cards) {
            CardTerms terms = analyse(card);
            perCard.add(terms);
            for (String key : terms.weight.keySet()) docFreq.merge(key, 1, Integer::sum);
        }

        // Pass 2: score, filter, keep the winners.
        int maxDocs = Math.max(1, (int) Math.floor(cards.size() * MAX_DOC_SHARE));
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (int i = 0; i < cards.size(); i++) {
            CardTerms terms = perCard.get(i);
            List<Map.Entry<String, Double>> scored = new ArrayList<>();
            for (var e : terms.weight.entrySet()) {
                int df = docFreq.get(e.getKey());
                if (df > maxDocs) continue;
                String printed = terms.surfaceFor(e.getKey());
                if (printed.length() < MIN_LENGTH) continue;
                if (ALL_DIGITS.matcher(printed).matches()) continue;
                if (stops.contains(printed.toLowerCase(Locale.ROOT))) continue;
                double idf = Math.log((double) cards.size() / df) + 1.0;
                scored.add(Map.entry(e.getKey(), e.getValue() * idf));
            }
            scored.sort(Comparator
                    .comparingDouble((Map.Entry<String, Double> e) -> e.getValue()).reversed()
                    .thenComparing(Map.Entry::getKey));
            List<String> winners = new ArrayList<>(MAX_PER_CARD);
            for (var e : scored) {
                if (winners.size() == MAX_PER_CARD) break;
                winners.add(perCard.get(i).surfaceFor(e.getKey()));
            }
            out.put(cards.get(i).id(), winners);
        }
        return out;
    }

    /** One card's candidate terms: weighted frequency per key, and each key's printable form. */
    private static final class CardTerms {
        final Map<String, Double> weight = new HashMap<>();
        /** key → surface form → how often the card used that spelling. */
        final Map<String, Map<String, Integer>> surfaces = new HashMap<>();

        void add(String key, String surface, double w) {
            weight.merge(key, w, Double::sum);
            surfaces.computeIfAbsent(key, k -> new HashMap<>())
                    .merge(surface, 1, Integer::sum);
        }

        /** The card's own most-used spelling of a term, ties broken alphabetically. */
        String surfaceFor(String key) {
            return surfaces.get(key).entrySet().stream()
                    .max(Map.Entry.<String, Integer>comparingByValue()
                            .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                    .orElseThrow().getKey();
        }
    }

    private static CardTerms analyse(Card card) {
        CardTerms terms = new CardTerms();
        StringBuilder body = new StringBuilder();
        StringBuilder headings = new StringBuilder();
        if (card.title() != null) headings.append(card.title()).append('\n');
        collect(card.blocks(), body, headings, terms);
        addWords(terms, body.toString(), 1.0);
        addWords(terms, headings.toString(), HEADING_WEIGHT);
        return terms;
    }

    private static void collect(List<Block> blocks, StringBuilder body,
                                StringBuilder headings, CardTerms terms) {
        for (Block b : blocks) {
            if (b.heading() != null) headings.append(b.heading()).append('\n');
            if (b.html() != null) {
                // Fenced code blocks are dropped before anything is counted —
                // only prose and the inline <code> spans woven through it say
                // what a card is about. Inline identifiers first, verbatim;
                // then the tag-stripped text.
                String html = PRE_BLOCK.matcher(b.html()).replaceAll(" ");
                Matcher code = CODE_SPAN.matcher(html);
                while (code.find()) {
                    String ident = unescape(TAG.matcher(code.group(1)).replaceAll("")).trim();
                    if (!ident.isEmpty() && ident.length() <= 40 && !ident.contains(" ")
                            && !ident.contains("\n")) {
                        terms.add("code:" + ident.toLowerCase(Locale.ROOT), ident, CODE_WEIGHT);
                    }
                }
                body.append(unescape(TAG.matcher(html).replaceAll(" "))).append('\n');
            }
            collect(b.children(), body, headings, terms);
        }
    }

    /** Stemmed word candidates from {@code text}, each occurrence at weight {@code w}. */
    private static void addWords(CardTerms terms, String text, double w) {
        try (EnglishAnalyzer analyzer = new EnglishAnalyzer();
             TokenStream stream = analyzer.tokenStream("", new StringReader(text))) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            // The stream sees stemmed tokens; the printable surface form is
            // recovered by re-reading the raw text in step with them. Lucene
            // has OffsetAttribute for exactly this.
            var offsets = stream.addAttribute(
                    org.apache.lucene.analysis.tokenattributes.OffsetAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                String stem = term.toString();
                String surface = text.substring(offsets.startOffset(), offsets.endOffset());
                if (stem.length() < 2) continue;
                terms.add("word:" + stem, surface, w);
            }
            stream.end();
        } catch (IOException e) {
            throw new LayoutException("Index term analysis failed", e);
        }
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&amp;", "&");
    }
}
