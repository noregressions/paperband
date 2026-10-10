package dev.noregressions.paperband;

import org.commonmark.node.Node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A back-of-book index: every {@code index} marker ({@link IndexTerms}) in the files being written
 * out, gathered into terms, exposed to templates as {@code site.index} in the post pass:
 *
 * <pre>
 * [% for group in site.index.letters %]
 * ## [[ group.letter ]]
 * [% for term in group.terms %]
 * - [[ term.term ]][% for at in term.locators %] [see](#[[ at.anchor ]])[% endfor %]
 * [% endfor %]
 * [% endfor %]
 * </pre>
 *
 * Terms sort by their plain text, ignoring case, Markdown and leading punctuation
 * ({@code `--add-opens`} sorts as "add-opens"), and terms that sort the same are one entry, shown
 * as first written. A term's locators are in output order: the sequence's files in order, each
 * file's markers in document order. A link to a locator with the {@code pageref} tag
 * ({@code [3.6](#...){! pageref}}) shows the page number in PDF output.
 */
public final class Index {

    private final List<Term> terms;

    private Index(List<Term> terms) {
        this.terms = terms;
    }

    /** The index of {@code files}, in that order. */
    static Index of(List<Site.FileView> files) {
        Map<String, Builder> byKey = new LinkedHashMap<>();
        for (Site.FileView f : files) {
            MarkdownFile file = f.file();
            if (file == null) {
                continue;
            }
            for (Node n : inOrder(file)) {
                Attributes a = file.getAttributes(n);
                if (a == null || !IndexTerms.marks(a)) {
                    continue;
                }
                String id = n instanceof org.commonmark.node.Heading h && h.getParent() == file ? file.headingId(h) : a.id();
                Locator at = new Locator(f, f.getAnchor() + "--" + id);
                for (IndexTerms.Term t : IndexTerms.of(a, n).terms()) {
                    Builder main = byKey.computeIfAbsent(key(t.main()), k -> new Builder(t.main()));
                    if (t.sub() == null) {
                        main.add(at);
                    } else {
                        main.subs.computeIfAbsent(key(t.sub()), k -> new Builder(t.sub())).add(at);
                    }
                }
            }
        }
        return new Index(sorted(byKey));
    }

    private static List<Node> inOrder(Node root) {
        List<Node> out = new ArrayList<>();
        for (Node c = root.getFirstChild(); c != null; c = c.getNext()) {
            out.add(c);
            out.addAll(inOrder(c));
        }
        return out;
    }

    /** Every term, sorted. */
    public List<Term> getTerms() {
        return terms;
    }

    /** The terms grouped by their first letter, in order; terms that start with a digit or symbol are under "#". */
    public List<Letter> getLetters() {
        Map<String, List<Term>> groups = new LinkedHashMap<>();
        for (Term t : terms) {
            groups.computeIfAbsent(t.getLetter(), k -> new ArrayList<>()).add(t);
        }
        return groups.entrySet().stream().map(e -> new Letter(e.getKey(), List.copyOf(e.getValue()))).toList();
    }

    public boolean isEmpty() {
        return terms.isEmpty();
    }

    /** The sort key: plain text, lower case, without Markdown or leading punctuation. */
    static String key(String term) {
        String plain = term.replaceAll("[`*_\\\\]", "").toLowerCase(Locale.ROOT).strip();
        String stripped = plain.replaceFirst("^[^\\p{L}\\p{N}]+", "");
        return stripped.isEmpty() ? plain : stripped;
    }

    private static List<Term> sorted(Map<String, Builder> builders) {
        return builders.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.<String>naturalOrder()))
                .map(e -> e.getValue().build(e.getKey()))
                .toList();
    }

    /** One letter's terms. */
    public record Letter(String letter, List<Term> terms) {

        public String getLetter() {
            return letter;
        }

        public List<Term> getTerms() {
            return terms;
        }
    }

    /** An entry: its term as first written, where it's marked, and its subentries. */
    public static final class Term {

        private final String term;
        private final String key;
        private final List<Locator> locators;
        private final List<Term> subentries;

        private Term(String term, String key, List<Locator> locators, List<Term> subentries) {
            this.term = term;
            this.key = key;
            this.locators = locators;
            this.subentries = subentries;
        }

        /** The term as first written, Markdown and all. */
        public String getTerm() {
            return term;
        }

        /** What it sorts by. */
        public String getKey() {
            return key;
        }

        /** The upper-case first letter of the sort key, or "#" for a digit or symbol. */
        public String getLetter() {
            return key.isEmpty() || !Character.isLetter(key.codePointAt(0)) ? "#"
                    : new String(Character.toChars(key.codePointAt(0))).toUpperCase(Locale.ROOT);
        }

        /** Where the term itself is marked; may be empty when only its subentries are. */
        public List<Locator> getLocators() {
            return locators;
        }

        public List<Term> getSubentries() {
            return subentries;
        }

        @Override
        public String toString() {
            return term;
        }
    }

    /** One place a term is marked. */
    public static final class Locator {

        private final Site.FileView file;
        private final String anchor;
        private boolean repeat;

        Locator(Site.FileView file, String anchor) {
            this.file = file;
            this.anchor = anchor;
        }

        /** The file it's in: {@code at.file.page.chapter}, say, labels it on the web. */
        public Site.FileView getFile() {
            return file;
        }

        /** The marked element's id in the output, for a link: {@code (#[[ at.anchor ]])}. */
        public String getAnchor() {
            return anchor;
        }

        /**
         * True if the term's previous locator is in the same file. A web index labelled by file
         * can hide repeats; in print each is a page number, and repeated pages are dropped.
         */
        public boolean isRepeat() {
            return repeat;
        }

        @Override
        public String toString() {
            return anchor;
        }
    }

    private static final class Builder {

        private final String term;
        private final List<Locator> locators = new ArrayList<>();
        private final Map<String, Builder> subs = new LinkedHashMap<>();

        Builder(String term) {
            this.term = term;
        }

        void add(Locator at) {
            if (locators.stream().anyMatch(l -> l.anchor.equals(at.anchor))) {
                return; // one element marked with the same term twice
            }
            Locator copy = new Locator(at.file, at.anchor);
            copy.repeat = !locators.isEmpty() && locators.getLast().file == at.file;
            locators.add(copy);
        }

        Term build(String key) {
            return new Term(term, key, List.copyOf(locators), sorted(subs));
        }
    }
}
