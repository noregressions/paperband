package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.config.BookPlan;
import dev.noregressions.paperband.model.Axis;
import dev.noregressions.paperband.model.BookConfig;
import dev.noregressions.paperband.model.NamedTemplates;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // ---- book-level config, otherwise read from the root paperband.yaml ----
    //
    // Declaring these here is what lets a book have no yaml at all: structure
    // and configuration in the POM, content in the markdown, appearance in CSS.
    // Anything declared here WINS over the root yaml -- it's a declaration, not
    // a default, and the POM is the file the author just edited. (Build
    // geometry outside this element -- <margins>, <pageSize> -- keeps the
    // opposite convention: it seeds the base and the yaml can still tune it.)

    /** Book title, used by the cover and the PDF metadata. */
    private String title;

    /** Full-page cover, as an image or a template. */
    private PageMatterConfig cover;

    /** Full-page back matter, as an image or a template. */
    private PageMatterConfig back;

    /** Running page header. */
    private PageMatterConfig header;

    /** Running page footer. */
    private PageMatterConfig footer;

    /**
     * Default landing/divider template for every section and part that doesn't
     * name its own — a built-in preset ({@code minimal}) or a template path.
     */
    private String sectionLandingTemplate;

    /**
     * Book-level template variables, reaching templates as {@code vars.*} —
     * {@code author}, {@code subtitle}, {@code series} and whatever else a
     * cover or footer template reads.
     *
     * <p>Flat string values only. Maven's configurator maps
     * {@code <vars><author>Name</author></vars>} onto a string map cleanly and
     * nested structures badly, so the nested config that matters has typed
     * parameters of its own instead ({@code <margins>}, {@code <pageSize>}).
     */
    private Map<String, String> vars = new LinkedHashMap<>();

    /**
     * Axes this book declares — the categorical dimensions that produce divider
     * pages, per-value landing pages and nav entries. Declared axes replace a
     * yaml {@code axes:} wholesale rather than merging by name: an axis list is
     * one structural statement, and two half-statements could only disagree.
     */
    private List<AxisConfig> axes = new ArrayList<>();

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

    /** @return the declared book title, or null */
    public String getTitle() {
        return title;
    }

    /** @return the declared cover, or null */
    public PageMatterConfig getCover() {
        return cover;
    }

    /** @return the declared back matter, or null */
    public PageMatterConfig getBack() {
        return back;
    }

    /** @return the declared running header, or null */
    public PageMatterConfig getHeader() {
        return header;
    }

    /** @return the declared running footer, or null */
    public PageMatterConfig getFooter() {
        return footer;
    }

    /** @return the declared default section landing template, or null */
    public String getSectionLandingTemplate() {
        return sectionLandingTemplate;
    }

    /** @return the declared book vars, never null */
    public Map<String, String> getVars() {
        return vars;
    }

    /** @return the declared axes, never null */
    public List<AxisConfig> getAxes() {
        return axes;
    }

    /** True when this element declares any book-level config at all. */
    boolean declaresBookConfig() {
        return title != null || cover != null || back != null || header != null
                || footer != null || sectionLandingTemplate != null || !vars.isEmpty()
                || !axes.isEmpty();
    }

    /**
     * Layer everything this element declares onto {@code base} — the config the
     * root yaml supplied, or an empty one when the book has no yaml.
     *
     * <p>Each field is independent: declaring a {@code <title>} doesn't clear a
     * yaml-declared cover. Parts are handled separately, since they're resolved
     * from patterns rather than copied across.
     *
     * @param base     the yaml-derived (or empty) book config
     * @param bookRoot the book root that page-matter templates resolve against
     * @return the merged config
     * @throws IllegalArgumentException if a declared page is empty
     */
    BookConfig mergeInto(BookConfig base, Path bookRoot) {
        Map<String, Object> mergedVars = new LinkedHashMap<>(base.vars());
        mergedVars.putAll(vars);
        return new BookConfig(
                base.bookRoot(),
                title != null ? title : base.title(),
                axes.isEmpty() ? base.axes() : resolvedAxes(bookRoot),
                base.globalCss(),
                mergedVars,
                base.targets(),
                base.theme(),
                sectionLandingTemplate != null
                        ? NamedTemplates.resolveSectionTemplate(bookRoot, sectionLandingTemplate.trim())
                        : base.sectionLandingTemplate(),
                base.cardSchema(),
                cover != null ? cover.toPageMatter(bookRoot, "cover") : base.cover(),
                back != null ? back.toPageMatter(bookRoot, "back") : base.back(),
                footer != null ? footer.toPageMatter(bookRoot, "footer") : base.footer(),
                header != null ? header.toPageMatter(bookRoot, "header") : base.header(),
                base.parts());
    }

    /** The declared axes as model {@link Axis} objects, in declaration order. */
    private List<Axis> resolvedAxes(Path bookRoot) {
        List<Axis> out = new ArrayList<>(axes.size());
        for (AxisConfig axis : axes) {
            out.add(axis.toAxis(bookRoot));
        }
        return out;
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
    /**
     * True when this element says which cards the book contains. False for a
     * {@code <book>} that only carries book-level config — the cards then come
     * from walking {@link #root}, exactly as {@code <input>} would, so a book
     * can declare its title and cover in the POM while still taking its
     * structure from the directory tree.
     */
    boolean declaresCardSelection() {
        return !parts.isEmpty() || !includes.isEmpty();
    }

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
                    "<book> declares no <parts> and no <includes> — nothing to select");
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
