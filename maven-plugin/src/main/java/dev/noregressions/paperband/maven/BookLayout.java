package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.config.BookOverlay;
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
 *   &lt;sections&gt;
 *     &lt;section&gt;
 *       &lt;title&gt;Execution Traces&lt;/title&gt;
 *       &lt;includes&gt;
 *         &lt;include&gt;services/&#42;/TRACE.md&lt;/include&gt;
 *       &lt;/includes&gt;
 *     &lt;/section&gt;
 *     &lt;section&gt;
 *       &lt;title&gt;Reference&lt;/title&gt;
 *       &lt;includes&gt;
 *         &lt;include&gt;docs/&#42;&#42;/&#42;.md&lt;/include&gt;
 *       &lt;/includes&gt;
 *     &lt;/section&gt;
 *   &lt;/sections&gt;
 * &lt;/book&gt;
 * </pre>
 *
 * <p>For the plain "just glob me these files" case, declare the patterns
 * directly on {@code <book>} and skip {@code <sections>} altogether — the cards
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
     * describe. Defaults to whatever the conventional geography resolved —
     * the {@code content/} wrapper, else {@code src/main/paperband}, else the
     * module basedir — so declaring a config-only {@code <book>} doesn't
     * re-root a conventionally laid-out project (see
     * {@code AbstractPaperbandMojo.bookRoot}). Relative paths resolve against
     * the module basedir.
     */
    private File root;

    /**
     * Declared sections, in emission order. Mutually exclusive with
     * {@link #includes}. May also carry one {@code <toc/>} marker (a
     * {@link Toc}) between the sections, placing the printed table of contents
     * at that point in the book.
     */
    private List<SectionConfig> sections = new ArrayList<>();

    /**
     * Glob patterns for a book with no declared sections — shorthand for a
     * single untitled section. Mutually exclusive with {@link #sections}.
     */
    private List<String> includes = new ArrayList<>();

    /** Glob patterns removing files {@link #includes} matched. */
    private List<String> excludes = new ArrayList<>();

    /**
     * Comma-separated frontmatter sort fields for the {@link #includes}
     * shorthand, e.g. {@code tier,-id}. Ignored when {@link #sections} is used —
     * each section declares its own.
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

    /**
     * The book's author, for the cover.
     *
     * <pre>
     * &lt;author&gt;Ada Lovelace&lt;/author&gt;
     * </pre>
     *
     * <p>For more than one, use {@link #authors} — repeating this element
     * doesn't stack, it overwrites, so declaring both is an error rather than a
     * silent loss.
     */
    private String author;

    /**
     * The book's authors, when there's more than one.
     *
     * <pre>
     * &lt;authors&gt;
     *   &lt;author&gt;Ada Lovelace&lt;/author&gt;
     *   &lt;author&gt;Grace Hopper&lt;/author&gt;
     * &lt;/authors&gt;
     * </pre>
     *
     * <p>Templates get the list as {@code book.authors} and a rendered form as
     * {@code book.author} ("Ada Lovelace and Grace Hopper"), so a theme written
     * for one author keeps working.
     */
    private List<String> authors = new ArrayList<>();

    /**
     * Render a back-of-book index, with real page numbers. {@code true}
     * builds it from each card's {@code index:} frontmatter list alone;
     * {@code auto} additionally extracts each card's distinctive terms from
     * its text (veto bad picks via {@code <vars><indexStop>}). Equivalent to
     * {@code vars: { index: true|auto }} in the root {@code paperband.yaml}.
     */
    private String index;

    /** Full-page cover, as an image or a template. */
    private PageMatterConfig cover;

    /** Full-page back matter, as an image or a template. */
    private PageMatterConfig back;

    /** Running page header. */
    private PageMatterConfig header;

    /** Running page footer. */
    private PageMatterConfig footer;

    /**
     * Default landing/divider template for every section that doesn't
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

    /** @return the declared book root, or null to take the conventional geography */
    public File getRoot() {
        return root;
    }

    /** @return the declared sections, never null */
    public List<SectionConfig> getSections() {
        return sections;
    }

    /** @return the section-less include patterns, never null */
    public List<String> getIncludes() {
        return includes;
    }

    /** @return the section-less exclude patterns, never null */
    public List<String> getExcludes() {
        return excludes;
    }

    /** @return the comma-separated sort fields for the section-less form, or null */
    public String getSort() {
        return sort;
    }

    /** @return the declared book title, or null */
    public String getTitle() {
        return title;
    }

    /** @return the single declared author, or null */
    public String getAuthor() {
        return author;
    }

    /** @return the declared authors, never null */
    public List<String> getAuthors() {
        return authors;
    }

    /**
     * Check what this element declares before anything acts on it.
     *
     * @throws IllegalArgumentException when it declares authorship two ways at
     *         once — one of them would have to be dropped, and dropping half of
     *         a declaration silently is how a book ends up with the wrong name
     *         on the cover — or an {@code <index>} mode that doesn't exist,
     *         which would otherwise silently mean "no index"
     */
    void validate() {
        if (author != null && !author.isBlank() && !authors.isEmpty()) {
            throw new IllegalArgumentException(
                    "<book> declares both <author> and <authors> — use one: <author> for a "
                            + "single name, <authors> for several.");
        }
        if (index != null) {
            String mode = index.trim().toLowerCase(java.util.Locale.ROOT);
            if (!mode.equals("true") && !mode.equals("false") && !mode.equals("auto")) {
                throw new IllegalArgumentException(
                        "<book><index> must be true, false or auto — got '" + index + "'. "
                                + "true indexes each card's index: frontmatter terms; auto also "
                                + "extracts each card's distinctive terms from its text.");
            }
        }
        long tocMarkers = sections.stream().filter(p -> p instanceof Toc).count();
        if (tocMarkers > 1) {
            throw new IllegalArgumentException(
                    "<sections> declares <toc/> " + tocMarkers + " times — there is one table of "
                            + "contents, so it can only sit in one place. Keep one.");
        }
        for (SectionConfig s : sections) {
            if (s instanceof Page page
                    && (page.getTemplate() == null || page.getTemplate().isBlank())) {
                throw new IllegalArgumentException(
                        "<sections> declares a <page> with no <template> — a page marker is "
                                + "nothing but its template: <page><template>matrix</template></page>, "
                                + "resolved against the book's layouts/ directory.");
            }
            if (s.getMisplacedPage() != null) {
                throw new IllegalArgumentException(
                        "<book> " + s + " declares a nested <page> — a page marker is positional, "
                                + "like <toc/>: put it BETWEEN <section> elements, directly under "
                                + "<sections>, and its template renders at that point in the book.");
            }
        }
    }

    /** True for the positional markers that live among {@code <section>}s but select no cards. */
    private static boolean isMarker(SectionConfig s) {
        return s instanceof Toc || s instanceof Page;
    }

    /**
     * Authors as declared, single or several, in order.
     *
     * @return the authors, empty when none is declared
     */
    List<String> resolvedAuthors() {
        if (author != null && !author.isBlank()) return List.of(author.trim());
        List<String> out = new ArrayList<>(authors.size());
        for (String a : authors) {
            if (a != null && !a.isBlank()) out.add(a.trim());
        }
        return out;
    }

    /**
     * The authors as one readable string — {@code "Ada Lovelace and Grace
     * Hopper"}, {@code "A, B and C"} — so a cover template that knows only
     * about {@code book.author} renders every declared name instead of one.
     */
    static String joinAuthors(List<String> names) {
        if (names.isEmpty()) return null;
        if (names.size() == 1) return names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1))
                + " and " + names.get(names.size() - 1);
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

    /**
     * Everything this element contributes to the template context: the declared
     * {@code <vars>}, plus authorship.
     *
     * <p>These have to enter through the config cascade rather than being
     * merged into the book config afterwards. A cover reads {@code book.author}
     * from the <em>render context's</em> vars, which are assembled while each
     * card is loaded — so a value attached to the book config later is a value
     * the cover never sees.
     *
     * @return the vars, in cascade form; empty when nothing is declared
     */
    Map<String, Object> declaredVars() {
        Map<String, Object> out = new LinkedHashMap<>(vars);
        List<String> declaredAuthors = resolvedAuthors();
        if (!declaredAuthors.isEmpty()) {
            out.put("author", joinAuthors(declaredAuthors));
            out.put("authors", declaredAuthors);
        }
        // The index reaches the layout the same way authorship does: through
        // the vars cascade, where the book model already looks for it. (The
        // printed TOC is positional — a <toc/> marker inside <sections> — and
        // travels through the book plan instead; see tocAfterSpec().)
        if (index != null) out.put("index", index);
        return out;
    }

    /** @return the declared axes, never null */
    public List<AxisConfig> getAxes() {
        return axes;
    }

    /** True when this element declares any book-level config at all. */
    boolean declaresBookConfig() {
        return title != null || cover != null || back != null || header != null
                || footer != null || sectionLandingTemplate != null || !vars.isEmpty()
                || !axes.isEmpty() || author != null || !authors.isEmpty()
                || index != null;
    }

    /**
     * Layer everything this element declares onto {@code base} — the config the
     * root yaml supplied, or an empty one when the book has no yaml.
     *
     * <p>Each field is independent: declaring a {@code <title>} doesn't clear a
     * yaml-declared cover. Sections are handled separately, since they're resolved
     * from patterns rather than copied across.
     *
     * @param base     the yaml-derived (or empty) book config
     * @param bookRoot the book root that page-matter templates resolve against
     * @return the merged config
     * @throws IllegalArgumentException if a declared page is empty
     */
    BookConfig mergeInto(BookConfig base, Path bookRoot) {
        return overlay(bookRoot).applyTo(base);
    }

    /**
     * This element's declarations as a {@link BookOverlay} — values only, in
     * model types, with no opinion about precedence. Which layer wins is
     * {@code BookOverlay}'s decision, made once for every build tool rather
     * than restated here.
     *
     * <p>The XML parsing stays where it belongs: Maven's configurator still
     * populates these fields from the POM, and the element classes
     * ({@link PageMatterConfig}, {@link AxisConfig}, …) still live in this
     * package. Only the merge left.
     *
     * @param bookRoot the root that declared templates and images resolve against
     * @return the overlay this element declares
     */
    BookOverlay overlay(Path bookRoot) {
        validate();
        BookOverlay.Builder b = BookOverlay.builder()
                .title(title)
                .axes(axes.isEmpty() ? null : resolvedAxes(bookRoot))
                .sectionLandingTemplate(sectionLandingTemplate == null
                        ? null
                        : NamedTemplates.resolveSectionTemplate(bookRoot, sectionLandingTemplate.trim()))
                .cover(cover   == null ? null : cover.toPageMatter(bookRoot, "cover"))
                .back(back     == null ? null : back.toPageMatter(bookRoot, "back"))
                .footer(footer == null ? null : footer.toPageMatter(bookRoot, "footer"))
                .header(header == null ? null : header.toPageMatter(bookRoot, "header"))
                .vars(new LinkedHashMap<String, Object>(vars));   // <vars> is String-valued XML
        // Authorship reaches templates through vars, where covers already look
        // for it: `author` rendered for the templates that know that name, and
        // `authors` as the list for those that want to lay several out.
        List<String> declaredAuthors = resolvedAuthors();
        if (!declaredAuthors.isEmpty()) {
            b.var("author", joinAuthors(declaredAuthors)).var("authors", declaredAuthors);
        }
        return b.var("index", index).build();
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
     * Translate this element into the ordered section specs {@code BookPlan}
     * resolves. Validation that needs no filesystem happens here so a
     * malformed POM fails before the tree is walked; everything about what
     * the patterns actually match is {@code BookPlan}'s business.
     *
     * @return the specs, in declared order
     * @throws IllegalArgumentException if the element declares both forms,
     *         neither form, or a section with no include patterns
     */
    /**
     * True when this element says which cards the book contains. False for a
     * {@code <book>} that only carries book-level config — the cards then come
     * from walking {@link #root}, exactly as {@code <input>} would, so a book
     * can declare its title and cover in the POM while still taking its
     * structure from the directory tree.
     */
    boolean declaresCardSelection() {
        return !sections.isEmpty() || !includes.isEmpty();
    }

    List<BookPlan.SectionSpec> toSpecs() {
        validate();
        List<SectionConfig> realSections = sections.stream().filter(p -> !isMarker(p)).toList();
        boolean hasSections = !realSections.isEmpty();
        boolean hasPatterns = !includes.isEmpty();
        if (realSections.size() < sections.size() && !hasSections) {
            // <toc/> and <page> mean "this page goes between these sections" —
            // with no sections around them there is no between.
            throw new IllegalArgumentException(
                    "<sections> declares only markers (<toc/>/<page>) and no <section> — a marker "
                            + "places its page between sections, so declare the sections it sits among");
        }
        if (hasSections && hasPatterns) {
            throw new IllegalArgumentException(
                    "<book> declares both <sections> and top-level <includes> — use one or the other "
                            + "(<includes> is shorthand for a single untitled section)");
        }
        if (!hasSections && !hasPatterns) {
            throw new IllegalArgumentException(
                    "<book> declares no <sections> and no <includes> — nothing to select");
        }

        if (hasPatterns) {
            return List.of(new BookPlan.SectionSpec(null, null, null, null,
                    includes, excludes, SectionConfig.splitFields(sort)));
        }

        List<BookPlan.SectionSpec> specs = new ArrayList<>(realSections.size());
        for (SectionConfig section : realSections) {
            if (section.getIncludes().isEmpty()) {
                throw new IllegalArgumentException(
                        "<book> " + section + " declares no <includes>");
            }
            specs.add(new BookPlan.SectionSpec(
                    section.getId(),
                    section.getTitle(),
                    section.getLandingTemplate(),
                    section.getWhere(),
                    section.getIncludes(),
                    section.getExcludes(),
                    section.sortFields(),
                    section.isLandingPage()));
        }
        return specs;
    }

    /**
     * Where the {@code <toc/>} marker sits among the declared sections: the
     * number of {@code <section>} elements before it — 0 places the contents
     * page before everything, {@code section count} after everything — or null
     * when no marker is declared. Indexes the specs {@link #toSpecs} returns,
     * which is what {@code BookPlan} turns into a card position. Other markers
     * ({@code <page>}) don't count: they produce no spec either.
     */
    Integer tocAfterSpec() {
        int before = 0;
        for (SectionConfig section : sections) {
            if (section instanceof Toc) return before;
            if (!isMarker(section)) before++;
        }
        return null;
    }

    /**
     * Every {@code <page>} marker among the declared sections, as (position,
     * template) pairs — the position counted in {@code <section>} elements,
     * exactly as {@link #tocAfterSpec()} counts, and the template already
     * resolved to a bare Pebble name. Empty when none is declared.
     */
    List<BookPlan.PageMarker> pageMarkers() {
        List<BookPlan.PageMarker> out = new ArrayList<>();
        int before = 0;
        for (SectionConfig section : sections) {
            if (section instanceof Page page) {
                out.add(new BookPlan.PageMarker(
                        before, NamedTemplates.templateName(page.getTemplate())));
            } else if (!isMarker(section)) {
                before++;
            }
        }
        return out;
    }
}
