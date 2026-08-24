package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.config.BookPlan;
import dev.noregressions.paperband.config.BookWalker;
import dev.noregressions.paperband.model.Part;

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
 * patterns and declared parts decide). Sharing this is what lets
 * {@code structure} describe exactly the book {@code build} would render,
 * declaration and all, instead of only ever describing the walk.
 */
final class BookSource {

    private BookSource() {}

    /**
     * A resolved book.
     *
     * @param root      the directory the build is rooted at — base URI for
     *                  relative assets, and the book whose config cascade applies
     * @param cardFiles the ordered card files
     * @param parts     declared parts grouping them, empty for a walked book
     *                  (whose grouping comes from the folder layout instead)
     */
    record Resolved(Path root, List<Path> cardFiles, List<Part> parts) {}

    /** Walk {@code input}'s directory tree. */
    static Resolved walk(Path input, String target, Log log) throws MojoFailureException {
        List<Path> cardFiles = new BookWalker(target).walk(input);
        if (cardFiles.isEmpty()) {
            throw new MojoFailureException("No cards found under " + input);
        }
        log.info("Found " + cardFiles.size() + " cards under " + input);
        return new Resolved(input, cardFiles, List.of());
    }

    /** Resolve a POM-declared {@code <book>} into its card list and parts. */
    static Resolved plan(Path root, List<BookPlan.PartSpec> specs, String target, Log log)
            throws MojoExecutionException, MojoFailureException {
        if (!Files.isDirectory(root)) {
            throw new MojoExecutionException("<book><root> is not a directory: " + root);
        }
        BookPlan.Plan resolved = BookPlan.resolve(root, specs, target);
        if (resolved.cards().isEmpty()) {
            throw new MojoFailureException("No cards matched the <book> patterns under " + root);
        }
        // Through the build's own log, not stderr: a warning nobody sees is the
        // same as no warning, and these are the ones that explain missing cards.
        for (String warning : resolved.warnings()) {
            log.warn(warning);
        }
        log.info("Planned " + resolved.cards().size() + " cards in " + resolved.parts().size()
                + (resolved.parts().size() == 1 ? " part under " : " parts under ") + root);
        for (Part part : resolved.parts()) {
            log.debug("  part " + part.id() + " (" + part.title() + "): " + part.cards().size() + " cards");
        }
        return new Resolved(root, resolved.cards(), resolved.parts());
    }

    /**
     * Resolve whichever source the goal was configured with.
     *
     * @param input the input path, or null when {@code plan} is set
     * @param plan  the POM-declared book, or null when {@code input} is set
     */
    static Resolved of(Path input, BookBuild.PlannedBook plan, String target, Log log)
            throws MojoExecutionException, MojoFailureException {
        if (plan != null) return plan(plan.root(), plan.specs(), target, log);
        if (input == null) throw new MojoExecutionException("No <input> and no <book> — nothing to build.");
        if (!Files.isDirectory(input)) {
            throw new MojoExecutionException("<input> must be a book directory: " + input);
        }
        return walk(input, target, log);
    }
}
