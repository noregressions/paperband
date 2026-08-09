package dev.noregressions.paperband.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Report page-spans per anchor from a rendered book PDF.
 *
 * <p>Sorted by start page (book reading order) by default. Use
 * {@code --by-pages} to sort by span descending.
 *
 * <pre>
 * pagewright pages /tmp/guide-book.pdf
 * pagewright pages /tmp/guide-book.pdf --by-pages
 * pagewright pages /tmp/guide-book.pdf --tier 2
 * pagewright pages /tmp/guide-book.pdf --cards-only
 * </pre>
 */
@Command(
        name = "pages",
        mixinStandardHelpOptions = true,
        description = "Report pages-per-anchor from a rendered book PDF.")
public final class PagesCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Input PDF file.")
    Path pdf;

    @Option(
            names = {"--by-pages"},
            description = "Sort by span descending (largest sections first).")
    boolean byPages;

    @Option(
            names = {"--tier"},
            description = "Filter to a specific tier (e.g. --tier 1). Omit for all.")
    Integer tier;

    @Option(
            names = {"--cards-only"},
            description = "Hide cover, axis-divider, and section-divider rows; cards only.")
    boolean cardsOnly;

    @Override
    public Integer call() throws Exception {
        if (!Files.isRegularFile(pdf)) {
            System.err.println("pages: input must be a PDF file: " + pdf);
            return 2;
        }

        List<PagesReport.Row> rows = PagesReport.analyse(pdf);
        if (rows.isEmpty()) {
            System.err.println("pages: no recognised anchors found in PDF — "
                    + "the renderer may not have emitted named destinations.");
            return 1;
        }

        if (cardsOnly) {
            rows = rows.stream().filter(r -> "card".equals(r.kind())).toList();
        }
        if (tier != null) {
            rows = filterByTier(rows, tier);
        }
        if (byPages) {
            rows = new ArrayList<>(rows);
            rows.sort(Comparator.<PagesReport.Row>comparingInt(PagesReport.Row::span).reversed()
                    .thenComparingInt(PagesReport.Row::startPage));
        }

        int totalSpan = rows.stream().mapToInt(PagesReport.Row::span).sum();
        int cardCount = (int) rows.stream().filter(r -> "card".equals(r.kind())).count();

        System.out.printf("%-8s  %-50s  %5s  %5s%n", "KIND", "ID", "START", "PAGES");
        System.out.println("--------  --------------------------------------------------  -----  -----");
        for (PagesReport.Row r : rows) {
            System.out.printf("%-8s  %-50s  %5d  %5d%n",
                    r.kind(), truncate(r.label(), 50), r.startPage(), r.span());
        }
        System.out.println();
        System.out.printf("Sum of shown spans: %d pages across %d card row(s)%n", totalSpan, cardCount);
        return 0;
    }

    /**
     * Filter rows to those belonging to the given tier. A row belongs to a
     * tier if the most recent {@code axis-divider-tier-N} divider preceding
     * it has the matching id, OR the row itself is that divider.
     *
     * <p>Specifically matches the {@code tier} axis's divider anchors
     * ({@code axis-divider-tier-{N}}) — this flag is a convenience for
     * books that declare a "tier" axis, not a generic per-axis filter. A
     * book with no "tier" axis simply won't have any {@code axis-divider-tier-}
     * anchors, so {@code --tier} matches nothing rather than erroring.
     */
    private static List<PagesReport.Row> filterByTier(List<PagesReport.Row> rows, int tier) {
        List<PagesReport.Row> out = new ArrayList<>();
        String prefix = "axis-divider-tier-";
        int currentTier = -1;
        for (PagesReport.Row r : rows) {
            if ("axis-divider".equals(r.kind()) && r.anchor().startsWith(prefix)) {
                try { currentTier = Integer.parseInt(r.anchor().substring(prefix.length())); }
                catch (NumberFormatException ignored) { currentTier = -1; }
            }
            if (currentTier == tier) out.add(r);
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}
