package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.config.BookPlan;
import dev.noregressions.paperband.config.BookWalker;
import dev.noregressions.paperband.model.PlacedPage;
import dev.noregressions.paperband.model.Section;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Deciding which cards a book contains, and in what order — the one step every
 * book-shaped goal ({@code build}, {@code site}, {@code structure}) does before
 * it does anything else.
 *
 * <p>Two sources, one result: walking a directory tree (folder layout and the
 * {@code order:} keys decide), or resolving a POM-declared {@code <book>} (glob
 * patterns and declared sections decide). Sharing this is what lets
 * {@code structure} describe exactly the book {@code build} would render,
 * declaration and all, instead of only ever describing the walk.
 */
final class BookSource {

    private BookSource() {}

    /**
     * A resolved book.
     *
     * @param root         the directory the build is rooted at — base URI for
     *                     relative assets, and the book whose config cascade applies
     * @param cardFiles    the ordered card files
     * @param sections     declared sections grouping them, empty for a walked book
     *                     (whose grouping comes from the folder layout instead)
     * @param tocCardIndex index into {@code cardFiles} before which the printed
     *                     table of contents renders (a {@code <toc/>} marker in
     *                     {@code <sections>}), or null when none was declared —
     *                     always null for a walked book
     * @param pages        generated pages placed into the card flow
     *                     ({@code <page>} markers in {@code <sections>}), each
     *                     already resolved to a card index; empty for a walked
     *                     book
     */
    record Resolved(Path root, List<Path> cardFiles, List<Section> sections, Integer tocCardIndex,
                    List<PlacedPage> pages) {}

    /** Walk {@code input}'s directory tree (legacy form: wrapper detection, md-first). */
    static Resolved walk(Path input, String target, Log log) throws MojoFailureException {
        List<Path> cardFiles = new BookWalker(target).walk(input);
        if (cardFiles.isEmpty()) {
            throw new MojoFailureException("No cards found under " + input);
        }
        log.info("Found " + cardFiles.size() + " cards under " + input);
        return new Resolved(input, cardFiles, List.of(), null, List.of());
    }

    /**
     * Walk a POM-resolved content root ({@code <content>}, or the
     * conventional {@code src/main/paperband/content}) — everything there is
     * content by declaration, so {@code .html} files are cards and no
     * wrapper detection applies.
     */
    static Resolved walkContent(Path contentRoot, String target, Log log) throws MojoFailureException {
        List<Path> cardFiles = new BookWalker(target).walkContent(contentRoot);
        if (cardFiles.isEmpty()) {
            throw new MojoFailureException("No cards found under " + contentRoot);
        }
        log.info("Found " + cardFiles.size() + " cards under " + contentRoot);
        return new Resolved(contentRoot, cardFiles, List.of(), null, List.of());
    }

    /**
     * Resolve a POM-declared {@code <book>} into its card list and sections.
     *
     * @param tocAfterSpec position of the {@code <toc/>} marker among the
     *        specs (see {@code BookLayout.tocAfterSpec()}), or null
     * @param pageMarkers  {@code <page>} markers among the specs (see
     *        {@code BookLayout.pageMarkers()})
     */
    static Resolved plan(Path root, List<BookPlan.SectionSpec> specs, Integer tocAfterSpec,
            List<BookPlan.PageMarker> pageMarkers, String target, Log log)
            throws MojoExecutionException, MojoFailureException {
        if (!Files.isDirectory(root)) {
            throw new MojoExecutionException("<book><root> is not a directory: " + root);
        }
        BookPlan.Plan resolved = BookPlan.resolve(root, specs, tocAfterSpec, pageMarkers, target);
        if (resolved.cards().isEmpty()) {
            throw new MojoFailureException("No cards matched the <book> patterns under " + root);
        }
        // Through the build's own log, not stderr: a warning nobody sees is the
        // same as no warning, and these are the ones that explain missing cards.
        for (String warning : resolved.warnings()) {
            log.warn(warning);
        }
        log.info("Planned " + resolved.cards().size() + " cards in " + resolved.sections().size()
                + (resolved.sections().size() == 1 ? " section under " : " sections under ") + root);
        for (Section section : resolved.sections()) {
            log.debug("  section " + section.id() + " (" + section.title() + "): " + section.cards().size() + " cards");
        }
        return new Resolved(root, resolved.cards(), resolved.sections(), resolved.tocCardIndex(),
                resolved.pages());
    }

    /**
     * Resolve whichever source the goal was configured with.
     *
     * @param input the input path, or null when {@code plan} is set
     * @param plan  the POM-declared book, or null when {@code input} is set
     */
    static Resolved of(Path input, BookBuild.PlannedBook plan, String target, Log log)
            throws MojoExecutionException, MojoFailureException {
        if (plan != null) return plan(plan.root(), plan.specs(), plan.tocAfterSpec(), plan.pageMarkers(), target, log);
        if (input == null) throw new MojoExecutionException("No <input> and no <book> — nothing to build.");
        if (!Files.isDirectory(input)) {
            throw new MojoExecutionException("<input> must be a book directory: " + input);
        }
        return walk(input, target, log);
    }
}
