package dev.noregressions.paperband;

import org.commonmark.node.Heading;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Index terms found by rule rather than marked by hand (a manifest's {@code index:} file). Each
 * file is searched as it's parsed, and the paragraph or heading where a term is first discussed
 * gets that term, as if it carried an {@code {! index="..."}} marker ({@link IndexTerms}):
 * <ul>
 *   <li>in each file, a term is placed once: on the first heading (level 2 or deeper) that
 *       mentions it, else the first paragraph or list item that does;</li>
 *   <li>a file needs at least {@code mentions} mentions of a term to be indexed for it, so
 *       passing mentions stay out; a term's home files are indexed whatever the count, at the
 *       file's opening paragraph if the term isn't mentioned at all;</li>
 *   <li>code blocks, tables, and the sections under {@code skip} headings (with their
 *       subsections) aren't searched; text in inline code is.</li>
 * </ul>
 * Hand-placed markers still work, and add to what's found here.
 */
public final class AutoIndex {

    /**
     * One term to find.
     *
     * @param term  as the index shows it, Markdown and {@code !subentry} allowed
     * @param match what to look for in the text
     * @param home  the files always indexed for it
     */
    public record Term(String term, Pattern match, Predicate<Path> home) {
    }

    private final List<Term> terms;
    private final Predicate<Path> files;
    private final Set<String> skip;
    private final int mentions;

    /**
     * @param files    the files searched
     * @param skip     headings whose sections aren't searched, any case
     * @param mentions mentions a file needs to be indexed for a term, unless it's the term's home
     */
    public AutoIndex(List<Term> terms, Predicate<Path> files, List<String> skip, int mentions) {
        this.terms = List.copyOf(terms);
        this.files = files;
        this.skip = skip.stream().map(s -> s.strip().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        this.mentions = mentions;
    }

    /** The pattern for a term with no {@code match:} of its own: its plain text, as whole words. */
    public static Pattern defaultMatch(String term) {
        String main = term.contains("!") ? term.substring(term.lastIndexOf('!') + 1) : term;
        String plain = main.replace("`", "").strip();
        String quoted = Pattern.quote(plain);
        String start = Character.isLetterOrDigit(plain.charAt(0)) ? "\\b" : "";
        String end = Character.isLetterOrDigit(plain.charAt(plain.length() - 1)) ? "\\b" : "";
        return Pattern.compile(start + quoted + end);
    }

    /**
     * The terms found in a parsed file, by the element that gets them, each as an index marker
     * value ({@code "JAXB; garbage collectors!G1"}); empty for a file that isn't searched.
     *
     * @param doc the file's root, after its markers are taken out
     */
    Map<Node, String> find(Path file, Node doc) {
        Map<Node, String> out = new LinkedHashMap<>();
        if (!files.test(file)) {
            return out;
        }
        List<Unit> units = units(doc);
        for (Term t : terms) {
            int count = 0;
            List<Unit> hits = new ArrayList<>();
            for (Unit u : units) {
                Matcher m = t.match().matcher(u.text);
                int n = 0;
                while (m.find()) {
                    n++;
                }
                if (n > 0) {
                    count += n;
                    hits.add(u);
                }
            }
            boolean home = t.home().test(file);
            if (!home && (count == 0 || count < mentions)) {
                continue;
            }
            Unit at = hits.stream().filter(u -> u.heading).findFirst()
                    .orElse(hits.isEmpty() ? null : hits.getFirst());
            if (at == null) {
                at = units.stream().filter(u -> !u.heading).findFirst().orElse(null);
            }
            if (at != null) {
                out.merge(at.target, t.term(), (a, b) -> a + "; " + b);
            }
        }
        return out;
    }

    private record Unit(Node target, String text, boolean heading) {
    }

    /** Headings (level 2 and deeper) and paragraphs, in document order, outside skipped sections. */
    private List<Unit> units(Node doc) {
        List<Unit> out = new ArrayList<>();
        int skipping = 0; // level of the skipped section's heading, or 0
        for (Node block = doc.getFirstChild(); block != null; block = block.getNext()) {
            if (block instanceof Heading h) {
                if (skipping > 0 && h.getLevel() > skipping) {
                    continue;
                }
                skipping = skip.contains(Site.text(h).strip().toLowerCase(Locale.ROOT)) ? h.getLevel() : 0;
            }
            if (skipping == 0) {
                collect(block, out);
            }
        }
        return out;
    }

    private static void collect(Node node, List<Unit> out) {
        switch (node) {
            case Heading h -> {
                if (h.getLevel() >= 2) {
                    out.add(new Unit(h, Site.text(h), true));
                }
            }
            case Paragraph p -> out.add(new Unit(
                    p.getParent() instanceof ListItem li && li.getFirstChild() == p ? li : p, Site.text(p), false));
            default -> {
                for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
                    collect(c, out);
                }
            }
        }
    }
}
