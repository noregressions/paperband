package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.maven.pdf.PagesReport;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reports how many pages each part of a rendered book PDF occupies — one row
 * per cover, divider and card, read from the PDF's named destinations.
 *
 * <p>Where {@code build}'s {@code <reportPages>} measures the HTML on the way
 * past, this reads a finished PDF, so it works on any Paperband output
 * whenever you want to look at it:
 *
 * <pre>mvn paperband:pages -Dpaperband.pdf=target/book.pdf</pre>
 */
// Verify, not process-resources: this reads a finished PDF, so it has to
// run after whatever produced one.
@Mojo(name = "pages", defaultPhase = LifecyclePhase.VERIFY,
        requiresProject = false, threadSafe = true)
public class PagesMojo extends AbstractPaperbandMojo {

    /** The rendered PDF to analyse. */
    @Parameter(property = "paperband.pdf", required = true)
    private java.io.File pdf;

    /** Sort by span descending (largest sections first) instead of book order. */
    @Parameter(property = "paperband.byPages", defaultValue = "false")
    private boolean byPages;

    /** Show only cards, hiding cover and divider rows. */
    @Parameter(property = "paperband.cardsOnly", defaultValue = "false")
    private boolean cardsOnly;

    /**
     * Show only the rows under this tier's divider. A convenience for books
     * that declare a {@code tier} axis; a book without one matches nothing.
     */
    @Parameter(property = "paperband.tier")
    private Integer tier;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipped("pages")) return;

        Path file = resolve(pdf);
        if (!Files.isRegularFile(file)) {
            throw new MojoExecutionException("<pdf> must be a PDF file: " + file);
        }

        List<PagesReport.Row> rows;
        try {
            rows = PagesReport.analyse(file);
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to analyse " + file + ": " + e.getMessage(), e);
        }
        if (rows.isEmpty()) {
            throw new MojoFailureException("No recognised anchors found in " + file
                    + " — the renderer may not have emitted named destinations.");
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

        getLog().info(String.format("%-8s  %-50s  %5s  %5s", "KIND", "ID", "START", "PAGES"));
        getLog().info("--------  --------------------------------------------------  -----  -----");
        for (PagesReport.Row r : rows) {
            getLog().info(String.format("%-8s  %-50s  %5d  %5d",
                    r.kind(), truncate(r.label(), 50), r.startPage(), r.span()));
        }
        getLog().info("");
        getLog().info(String.format("Sum of shown spans: %d pages across %d card row(s)",
                totalSpan, cardCount));
    }

    /**
     * Rows belonging to one tier: a row belongs if the most recent
     * {@code axis-divider-tier-N} preceding it has the matching id, or is
     * itself that divider.
     */
    private static List<PagesReport.Row> filterByTier(List<PagesReport.Row> rows, int tier) {
        List<PagesReport.Row> out = new ArrayList<>();
        String prefix = "axis-divider-tier-";
        int currentTier = -1;
        for (PagesReport.Row r : rows) {
            if ("axis-divider".equals(r.kind()) && r.anchor().startsWith(prefix)) {
                try {
                    currentTier = Integer.parseInt(r.anchor().substring(prefix.length()));
                } catch (NumberFormatException ignored) {
                    currentTier = -1;
                }
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
