package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.maven.pdf.PagesReport;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.RenderContext;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-render page-count analysis: the report {@code <reportPages>} asks for,
 * and the enforcement a page contract asks for.
 *
 * <p>Measures the just-rendered HTML via {@link PagesReport#analyseHtmlFull} —
 * a Playwright DOM-measurement pass rather than a read of the finished PDF's
 * named destinations; see that class for why the build-time path works that
 * way.
 *
 * <p>The effective limit for a card, most specific first:
 * <ol>
 *   <li>{@code max_pages} in the card's own frontmatter</li>
 *   <li>{@code <maxPagesPerCard>} from the POM</li>
 *   <li>{@code vars.maxPagesPerCard} from {@code paperband.yaml} — any level of
 *       the cascade, same as any other vars entry</li>
 *   <li>otherwise unenforced</li>
 * </ol>
 */
final class PageChecks {

    private final Log log;
    private final boolean reportPages;
    private final Integer declaredLimit;

    PageChecks(Log log, boolean reportPages, Integer declaredLimit) {
        this.log = log;
        this.reportPages = reportPages;
        this.declaredLimit = declaredLimit;
    }

    /** A global ceiling and where it came from, for reporting and error messages. */
    private record GlobalLimit(int value, String source) {}

    /**
     * Report and/or enforce.
     *
     * @throws MojoFailureException when any card exceeds its effective limit —
     *         a page contract is a build contract, so breaking it fails the build
     */
    void run(List<Card> cards, String html, URI baseUri, RenderContext bookCtx)
            throws MojoFailureException {
        Map<String, Integer> frontmatterLimits = new HashMap<>();
        for (Card c : cards) {
            Integer n = parseInt(c.frontmatter().values().get("max_pages"));
            if (n != null && n > 0) frontmatterLimits.put(c.id(), n);
        }

        GlobalLimit globalLimit = resolveGlobalLimit(bookCtx);
        boolean haveAnyLimit = !frontmatterLimits.isEmpty() || globalLimit != null;
        if (!reportPages && !haveAnyLimit) return;

        PagesReport.Analysis analysis;
        try {
            analysis = PagesReport.analyseHtmlFull(html, baseUri, bookCtx.pageSpec());
        } catch (Exception e) {
            log.warn("failed to analyse pages: " + e.getMessage());
            return;
        }
        List<PagesReport.Row> rows = analysis.rows();
        if (rows.isEmpty()) {
            log.warn("no recognised anchors found — page-count checks skipped.");
            return;
        }

        if (reportPages) {
            report(rows, frontmatterLimits, globalLimit);
        }
        if (!haveAnyLimit) return;

        enforce(analysis, rows, frontmatterLimits, globalLimit);
    }

    private void report(List<PagesReport.Row> rows,
                        Map<String, Integer> frontmatterLimits,
                        GlobalLimit globalLimit) {
        log.info("");
        log.info(String.format("%-8s  %-50s  %5s  %5s  %5s", "KIND", "ID", "START", "PAGES", "LIMIT"));
        log.info("--------  --------------------------------------------------  -----  -----  -----");
        for (PagesReport.Row r : rows) {
            String limitCol = "";
            if ("card".equals(r.kind())) {
                Integer fm = frontmatterLimits.get(r.label());
                if (fm != null) {
                    limitCol = String.valueOf(fm);
                } else if (globalLimit != null) {
                    limitCol = globalLimit.value() + "*";
                }
            }
            log.info(String.format("%-8s  %-50s  %5d  %5d  %5s",
                    r.kind(), truncate(r.label(), 50), r.startPage(), r.span(), limitCol));
        }
        if (globalLimit != null) {
            log.info("(* = global limit from " + globalLimit.source() + ")");
        }
    }

    /**
     * Enforce card by card, naming the pages that actually overflow and, where
     * it can, the section that first crosses the limit.
     */
    private void enforce(PagesReport.Analysis analysis,
                         List<PagesReport.Row> rows,
                         Map<String, Integer> frontmatterLimits,
                         GlobalLimit globalLimit) throws MojoFailureException {
        record Offender(String cardId, String anchor, int startPage, int pages, int limit, String source) {}
        List<Offender> offenders = new ArrayList<>();
        int checkedCount = 0;
        for (PagesReport.Row r : rows) {
            if (!"card".equals(r.kind())) continue;
            Integer fm = frontmatterLimits.get(r.label());
            int limit;
            String source;
            if (fm != null) {
                limit = fm;
                source = "frontmatter";
            } else if (globalLimit != null) {
                limit = globalLimit.value();
                source = globalLimit.source();
            } else {
                continue;
            }
            checkedCount++;
            if (r.span() > limit) {
                offenders.add(new Offender(r.label(), r.anchor(), r.startPage(), r.span(), limit, source));
            }
        }

        if (offenders.isEmpty()) {
            log.info("Page-count check passed: " + checkedCount + " card(s) checked, all within their limits.");
            return;
        }

        StringBuilder message = new StringBuilder("Page-count check failed: ")
                .append(offenders.size()).append(" card(s) exceeded their page limit.");
        for (Offender o : offenders) {
            int lastAllowedPage = o.startPage() + o.limit() - 1;
            int lastActualPage = o.startPage() + o.pages() - 1;
            String overflowPages = (lastAllowedPage + 1 == lastActualPage)
                    ? "page " + lastActualPage
                    : "pages " + (lastAllowedPage + 1) + "-" + lastActualPage;
            message.append(String.format(
                    "%n  %s: pages %d-%d (%d pages, limit %d, from %s) — %s overflow",
                    o.cardId(), o.startPage(), lastActualPage, o.pages(), o.limit(), o.source(),
                    overflowPages));
            String detail = PagesReport.firstOverflowUnit(analysis, o.anchor(), o.limit())
                    .map(loc -> loc.label() != null
                            ? String.format("%n      first crossed by \"%s\" (starts page %d)",
                                    loc.label(), loc.page())
                            : String.format("%n      first crossed within an unlabelled section (starts page %d)",
                                    loc.page()))
                    .orElse(String.format("%n      (no section-level detail available for this card)"));
            message.append(detail);
        }
        throw new MojoFailureException(message.toString());
    }

    /**
     * The global ceiling: the POM's {@code <maxPagesPerCard>} wins when set —
     * same "the build overrides the book" convention {@code <theme>} follows —
     * otherwise {@code vars.maxPagesPerCard} from the yaml cascade.
     */
    private GlobalLimit resolveGlobalLimit(RenderContext bookCtx) {
        if (declaredLimit != null) return new GlobalLimit(declaredLimit, "<maxPagesPerCard>");
        Integer yamlValue = parseInt(bookCtx.vars().get("maxPagesPerCard"));
        if (yamlValue != null && yamlValue > 0) return new GlobalLimit(yamlValue, "paperband.yaml");
        return null;
    }

    private static Integer parseInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}
