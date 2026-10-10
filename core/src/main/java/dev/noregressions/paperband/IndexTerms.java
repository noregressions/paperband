package dev.noregressions.paperband;

import org.commonmark.node.Node;

import java.util.ArrayList;
import java.util.List;

/**
 * The index terms an {@code index} marker gives an element:
 *
 * <pre>
 * Removed in JDK 11. {! index="JAXB"}                  a term, for the paragraph
 * {! index="JAXB; jakarta.*!framework move"}            several terms, ; between them; ! makes a subentry
 * ## Removed Java EE packages {! index="Java EE"}       a heading
 * The [JAXB]{! index} runtime                           no value: the element's own text is the term
 * Run `jdeps`{! index} first                            ... of a code span, too
 * </pre>
 *
 * A term may use inline Markdown ({@code `jakarta.*`}); it sorts as its plain text. The element
 * gets an id ({@link Markers}) so the index ({@link Site#getIndex()}) can link to it.
 */
public record IndexTerms(List<Term> terms) {

    /** The marker's name. */
    public static final String KEY = "index";

    /** One term: a main entry, and a subentry under it or null. */
    public record Term(String main, String sub) {
    }

    /** True if the handles include an index marker. */
    static boolean marks(Attributes a) {
        return a.values().containsKey(KEY) || a.tags().contains(KEY);
    }

    /**
     * The terms the handles give {@code node}.
     *
     * @throws Attributes.MarkerException for an empty term or subentry, or a bare {@code index}
     *                                    on an element with no text
     */
    static IndexTerms of(Attributes a, Node node) {
        String spec = a.values().get(KEY);
        List<Term> terms = new ArrayList<>();
        if (a.tags().contains(KEY) && spec != null) {
            terms.add(of(new Attributes(null, List.of(KEY), java.util.Map.of()), node).terms().getFirst());
        }
        if (spec == null) {
            String own = Site.text(node).strip();
            if (own.isEmpty()) {
                throw new Attributes.MarkerException("{! index} with no value uses the element's text as the term,"
                        + " but this element has none; give one: {! index=\"term\"}");
            }
            return new IndexTerms(List.of(new Term(own, null)));
        }
        for (String part : spec.split(";")) {
            String t = part.strip();
            int bang = t.indexOf('!');
            String main = (bang < 0 ? t : t.substring(0, bang)).strip();
            String sub = bang < 0 ? null : t.substring(bang + 1).strip();
            if (main.isEmpty() || (sub != null && sub.isEmpty())) {
                throw new Attributes.MarkerException("index=\"" + spec + "\" has an empty term: write terms as"
                        + " \"term\", \"term!subentry\", with ; between several");
            }
            if (sub != null && sub.contains("!")) {
                throw new Attributes.MarkerException("index=\"" + spec + "\": a subentry can't have subentries of its"
                        + " own (one ! per term)");
            }
            terms.add(new Term(main, sub));
        }
        return new IndexTerms(List.copyOf(terms));
    }
}
