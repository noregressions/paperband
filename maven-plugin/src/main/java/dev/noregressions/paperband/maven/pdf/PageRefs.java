package dev.noregressions.paperband.maven.pdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The second half of two-pass page numbering, for the printed TOC and
 * back-of-book index.
 *
 * <p>Page numbers don't exist at HTML time — Chromium decides pagination —
 * so the layout renders every one as a placeholder span keyed by the anchor
 * it should point at:
 *
 * <pre>{@code <span class="pw-pageref" data-pw-anchor="card-intro">000</span>}</pre>
 *
 * The build renders the book once, reads each anchor's real start page from
 * that PDF's named destinations ({@link PagesReport#analyse}), substitutes
 * the numbers into the HTML here, and renders again. The substitution only
 * changes text <em>inside</em> the spans — a page number where "000" stood —
 * so pagination holds between the passes and the second PDF's numbers are
 * exact.
 *
 * <p>An anchor with no destination in the first pass (a reference to a page
 * that doesn't exist) renders as {@code ?} rather than a silently wrong
 * number, and the build warns naming it.
 */
public final class PageRefs {

    private PageRefs() {}

    /** The marker class — its presence in the HTML is what turns the second pass on. */
    public static final String MARKER = "pw-pageref";

    private static final Pattern REF = Pattern.compile(
            "(<span class=\"[^\"]*pw-pageref[^\"]*\" data-pw-anchor=\"([^\"]+)\">)[^<]*(</span>)");

    /** The substituted HTML plus what happened: how many refs resolved, and which anchors didn't. */
    public record Resolved(String html, int resolved, List<String> unresolved) {}

    /** Whether {@code html} contains any page-reference placeholders at all. */
    public static boolean present(String html) {
        return html.contains(MARKER);
    }

    /**
     * Each recognised anchor's start page in {@code pdf}, read from its named
     * destinations — the input {@link #resolve} needs.
     */
    public static Map<String, Integer> readAnchorPages(Path pdf) throws IOException {
        Map<String, Integer> pages = new LinkedHashMap<>();
        for (PagesReport.Row row : PagesReport.analyse(pdf)) {
            pages.put(row.anchor(), row.startPage());
        }
        return pages;
    }

    /**
     * Replace every placeholder's text with its anchor's page number.
     *
     * @param html  the pass-one HTML, placeholders intact
     * @param pages anchor → start page, from {@link #readAnchorPages}
     */
    public static Resolved resolve(String html, Map<String, Integer> pages) {
        List<String> unresolved = new ArrayList<>();
        int[] resolved = {0};
        Matcher m = REF.matcher(html);
        String out = m.replaceAll(match -> {
            String anchor = match.group(2);
            Integer page = pages.get(anchor);
            if (page == null) {
                if (!unresolved.contains(anchor)) unresolved.add(anchor);
                return Matcher.quoteReplacement(match.group(1) + "?" + match.group(3));
            }
            resolved[0]++;
            return Matcher.quoteReplacement(match.group(1) + page + match.group(3));
        });
        return new Resolved(out, resolved[0], List.copyOf(unresolved));
    }
}
