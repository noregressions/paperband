package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.config.BookPlan;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code <book>} element of the {@code build} goal: a book's structure
 * declared in the POM instead of inferred from the directory tree.
 *
 * <p>Where {@code <input>} hands a directory to the book walker and lets the
 * folder layout (plus any {@code paperband.yaml} sequencing keys) decide what
 * the book contains and how it's grouped, {@code <book>} states it outright
 * and selects the cards by glob:
 *
 * <pre>
 * &lt;book&gt;
 *   &lt;root&gt;${project.basedir}&lt;/root&gt;
 *   &lt;parts&gt;
 *     &lt;part&gt;
 *       &lt;title&gt;Execution Traces&lt;/title&gt;
 *       &lt;includes&gt;
 *         &lt;include&gt;services/&#42;/TRACE.md&lt;/include&gt;
 *       &lt;/includes&gt;
 *     &lt;/part&gt;
 *     &lt;part&gt;
 *       &lt;title&gt;Reference&lt;/title&gt;
 *       &lt;includes&gt;
 *         &lt;include&gt;docs/&#42;&#42;/&#42;.md&lt;/include&gt;
 *       &lt;/includes&gt;
 *     &lt;/part&gt;
 *   &lt;/parts&gt;
 * &lt;/book&gt;
 * </pre>
 *
 * <p>For the plain "just glob me these files" case, declare the patterns
 * directly on {@code <book>} and skip {@code <parts>} altogether — the cards
 * are emitted in pattern order and grouped by their own folders, exactly as
 * walked cards are:
 *
 * <pre>
 * &lt;book&gt;
 *   &lt;root&gt;${project.basedir}/services&lt;/root&gt;
 *   &lt;includes&gt;
 *     &lt;include&gt;&#42;/TRACE.md&lt;/include&gt;
 *   &lt;/includes&gt;
 * &lt;/book&gt;
 * </pre>
 *
 * <p>{@code <root>} still needs to be the book root — the directory whose
 * {@code paperband.yaml} carries the title, css, theme, vars and cover, since
 * that side of the config is resolved from each card's own parent chain and
 * not from this element. Patterns are relative to it, and cards outside it
 * would resolve against a different book.
 */
public class BookLayout {

    /**
     * The book root: patterns resolve against it, and its
     * {@code paperband.yaml} supplies everything this element doesn't
     * describe. Defaults to the module's basedir; relative paths resolve
     * against it.
     */
    private File root;

    /** Declared parts, in emission order. Mutually exclusive with {@link #includes}. */
    private List<PartConfig> parts = new ArrayList<>();

    /**
     * Glob patterns for a book with no declared parts — shorthand for a
     * single untitled part. Mutually exclusive with {@link #parts}.
     */
    private List<String> includes = new ArrayList<>();

    /** Glob patterns removing files {@link #includes} matched. */
    private List<String> excludes = new ArrayList<>();

    /**
     * Comma-separated frontmatter sort fields for the {@link #includes}
     * shorthand, e.g. {@code tier,-id}. Ignored when {@link #parts} is used —
     * each part declares its own.
     */
    private String sort;

    /** @return the declared book root, or null for the module basedir */
    public File getRoot() {
        return root;
    }

    /** @return the declared parts, never null */
    public List<PartConfig> getParts() {
        return parts;
    }

    /** @return the part-less include patterns, never null */
    public List<String> getIncludes() {
        return includes;
    }

    /** @return the part-less exclude patterns, never null */
    public List<String> getExcludes() {
        return excludes;
    }

    /** @return the comma-separated sort fields for the part-less form, or null */
    public String getSort() {
        return sort;
    }

    /**
     * Translate this element into the ordered part specs {@code BookPlan}
     * resolves. Validation that needs no filesystem happens here so a
     * malformed POM fails before the tree is walked; everything about what
     * the patterns actually match is {@code BookPlan}'s business.
     *
     * @return the specs, in declared order
     * @throws IllegalArgumentException if the element declares both forms,
     *         neither form, or a part with no include patterns
     */
    List<BookPlan.PartSpec> toSpecs() {
        boolean hasParts = !parts.isEmpty();
        boolean hasPatterns = !includes.isEmpty();
        if (hasParts && hasPatterns) {
            throw new IllegalArgumentException(
                    "<book> declares both <parts> and top-level <includes> — use one or the other "
                            + "(<includes> is shorthand for a single untitled part)");
        }
        if (!hasParts && !hasPatterns) {
            throw new IllegalArgumentException(
                    "<book> declares no <parts> and no <includes> — nothing to build");
        }

        if (hasPatterns) {
            return List.of(new BookPlan.PartSpec(null, null, null, null,
                    includes, excludes, PartConfig.splitFields(sort)));
        }

        List<BookPlan.PartSpec> specs = new ArrayList<>(parts.size());
        for (PartConfig part : parts) {
            if (part.getIncludes().isEmpty()) {
                throw new IllegalArgumentException(
                        "<book> " + part + " declares no <includes>");
            }
            specs.add(new BookPlan.PartSpec(
                    part.getId(),
                    part.getTitle(),
                    part.getLandingTemplate(),
                    part.getWhere(),
                    part.getIncludes(),
                    part.getExcludes(),
                    part.sortFields(),
                    part.isLandingPage()));
        }
        return specs;
    }
}
