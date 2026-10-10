package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.FrontMatter;
import dev.noregressions.paperband.MarkdownPipeline;
import dev.noregressions.paperband.Variants;
import org.jsoup.select.Evaluator;
import org.jsoup.select.Selector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A page layout: the page geometry and a list of boxes, each filled with AST content picked by
 * a CSS selector. Loaded from {@code layouts/<name>.yml}:
 *
 * <pre>
 * page: { size: A4, margin: 15mm }     # optional; defaults to the manifest's defaults.page
 * class: chapter-page                  # optional extra class on the page's &lt;section&gt;
 * unplaced: warn                       # content no box places: warn (default) | ignore | error
 * prefer: short                        # the variant shown of each variants group (default: the longest)
 * stylesheet: hero.css                 # optional CSS, relative to this file, confined to the layout (see CssScoper)
 * css: |                               # optional CSS written right here, confined the same way
 *   h1 { color: navy }
 * stack: { gap: 6mm }                  # or: boxes one above another (with grid, choose one)
 * grid:                                # optional named areas (see Grid)
 *   columns: [1fr, 2fr]
 *   areas: ["title title", "intro body"]
 * boxes:
 *   - id: title                        # required, unique within the layout
 *     class: hero-title                # optional extra class on the box's &lt;div&gt;
 *     select: h1                       # a CSS selector (see AstView), or "rest"
 *     pick: first                      # first | last | N (1-based); applied per file; default: all
 *     scope: page                      # page (this file) | sequence | sources
 *     as: html                         # html | text | list | links
 *     at: { x: 15mm, y: 15mm, w: 180mm, h: 30mm }   # optional position on the page
 *     area: title                      # with a grid, the area the box goes in (required then)
 *     wrap: true                       # false: no &lt;div&gt; around the box's content
 *   - id: body
 *     select: rest                     # whatever no other page-scoped box took
 *     split:                           # may continue on further pages (see {@link Split}); split: true for the defaults
 *       between: "section.h2"          # where it may break (default: between its blocks, or each's matches)
 * </pre>
 *
 * Other kinds of box:
 *
 * <pre>
 *   - { id: label, text: Contents }                       # fixed text
 *   - { id: md, content: source, stage: original }        # the page's markdown (original | pre | final)
 *   - { id: file, content: path }                         # the page's file path, as text
 *   - { id: uses, content: layout }                       # the page's own layout name (as layout: page picks it)
 *   - { id: yaml, content: layout-source }                # that layout's file, highlighted
 *   - { id: level, value: level, lookup: levels }         # a front matter value (see {@link Kind#VALUE})
 *   - { id: title, value: title, heading: 1 }             # ... shown as a heading, an &lt;h1&gt;
 *   - { id: result, layout: page }                        # the file through its own layout
 *   - { id: card, select: "#summary", layout: card }      # selected content through a named layout
 *   - id: steps                                           # one inner layout per match
 *     each: ".step"
 *     layout: step-card                                   # a named layout, or nested boxes:
 *     boxes: [ { id: title, select: h3 }, ... ]           #   boxes (plus grid, unplaced) right here
 * </pre>
 *
 * Inside an inner layout, {@code scope: page} and {@code rest} mean the content the outer box
 * passed in (the match, the selection, or the whole file), not the whole page.
 * The same keys can be written in a markdown file's front matter, under {@code layout:}, to give
 * that one file an inline layout (see {@link #inline}). Either kind can start from another
 * layout with {@code extends} and change only some boxes (see {@link LayoutExtension}).
 * <p>
 * Web output flows boxes in order (or into the grid's areas) and ignores {@code page} and
 * {@code at}; they're for PDF and PPTX.
 */
public record Layout(String name, PageSpec page, String cssClass, Grid grid, Stack stack, Unplaced unplaced,
                     String css, Variants.Variant prefer, List<Box> boxes, List<Remedy> fit, Map<String, Grid> grids) {

    public Layout(String name, PageSpec page, String cssClass, Grid grid, Stack stack, Unplaced unplaced,
                  String css, Variants.Variant prefer, List<Box> boxes) {
        this(name, page, cssClass, grid, stack, unplaced, css, prefer, boxes, List.of(), Map.of());
    }

    /** Boxes one above another, in order, with a gap: {@code stack: { gap: 6mm }}. */
    public record Stack(String gap) {
    }

    /** True if the layout gives its page a fixed shape: a grid, a stack, or a box with {@code at}. */
    public boolean fixed() {
        return grid != null || stack != null || boxes.stream().anyMatch(b -> b.at() != null);
    }

    public enum Scope { PAGE, SEQUENCE, SOURCES }

    public enum As { HTML, TEXT, LIST, LINKS }

    /** What happens to content no box places, in a layout without a rest box. */
    public enum Unplaced { WARN, IGNORE, ERROR }

    /** What fills a box. */
    public enum Kind {
        /** AST nodes chosen by a selector. */
        SELECT,
        /** What no other box in the layout took. */
        REST,
        /** Fixed text. */
        TEXT,
        /** The page's markdown. */
        SOURCE,
        /** The page's file path. */
        PATH,
        /** The name of the page's own layout. */
        OWN_LAYOUT,
        /** The YAML of the page's own layout. */
        LAYOUT_SOURCE,
        /** One inner layout per selector match. */
        EACH,
        /** One inner layout over the selection (or the whole content). */
        LAYOUT,
        /**
         * A value from the page's front matter ({@code value: symptom}, dotted for nested keys),
         * shown as inline markdown (markers included) or, with {@code as: text}, as plain text;
         * a list shows as a list. With {@code lookup: NAME}, the value is a key into
         * {@code data.NAME}, and that entry (its {@code name}, if it's a map) is shown instead.
         * A value that's an id ({@code wont-start}) also becomes a class on the box:
         * {@code level-wont-start}.
         */
        VALUE
    }

    /** How much a box matters when space runs out: essential boxes never give way. */
    public enum Priority { ESSENTIAL, NORMAL, OPTIONAL }

    /** What happens to the space a dropped box leaves. */
    public enum Space { COLLAPSE, LEAVE }

    /**
     * When a box appears at all. All conditions must hold; otherwise the box isn't on the page
     * (no div, no warnings), and anything it would have taken is left for {@code rest}.
     *
     * @param notEmpty only if it found something
     * @param page     a front matter filter, as in a manifest's {@code where}; null for none
     * @param outputs  only in these outputs; empty for all
     */
    public record When(boolean notEmpty, Where page, List<String> outputs) {
        public static final When ALWAYS = new When(false, null, List.of());
    }

    /** Size limits in millimetres, in the box's grid row or column or its stack slot; null for none. */
    public record Size(Double min, Double max) {
    }

    /**
     * @param belowMin fitting may drop the box when even its {@code size.min} can't be spared
     * @param space    what happens to the space it leaves
     */
    public record Drop(boolean belowMin, Space space) {
    }

    /** How {@code {! break=...}} and {@code {! keep}} markers in a splitting box's content are used. */
    public enum BreakMarkers {
        /** They only choose among the break points the layout allows. */
        NARROW,
        /** They can also make new break points. */
        ADD,
        /** They're ignored. */
        IGNORE
    }

    /**
     * A box that may continue on further pages when its content doesn't fit:
     *
     * <pre>
     * split:
     *   between: ".step"              # units it may break between (default: its blocks, or each's matches)
     *   keep-together: ["pre"]        # never break inside these
     *   repeat: "h2:first-of-type"    # repeated at the top of each continuation
     *   label: continued              # shown after the repeated heading: "Steps (continued)"
     *   min-units: 2                  # at least this many units on a page (default 1)
     *   prefer-fill: 60%              # a {! break=prefer} is taken if the page is at least this full (default 50%)
     *   markers: narrow               # narrow (default) | add | ignore
     *   continue: steps-continued     # the layout for continuation pages (default: this one)
     *   into: steps                   # the box there that takes the rest (default: this box's id)
     * </pre>
     *
     * On a continuation page the {@code into} box takes the rest; the layout's other boxes that read
     * the page's file are left out, so content isn't shown twice.
     *
     * @param between        the unit selector, or null for the box's own blocks (an each box's matches)
     * @param continueLayout the continuation layout's name, or null for the box's own layout
     * @param into           the box that takes the rest there, or null for this box's id
     */
    public record Split(String between, Evaluator betweenEvaluator, List<String> keepTogether,
                        List<Evaluator> keepEvaluators, String repeat, Evaluator repeatEvaluator, String label,
                        int minUnits, double preferFill, BreakMarkers markers, String continueLayout, String into) {

        /** The continuation layout's name, for a box in {@code layout}. */
        public String continueIn(Layout layout) {
            return continueLayout != null ? continueLayout : layout.name();
        }

        /** The box that takes the rest on a continuation page. */
        public String intoBox(Box box) {
            return into != null ? into : box.id();
        }
    }

    /**
     * A box's rules; see {@link When}, {@link Priority}, {@link Size}, {@link Drop}, {@link Split}.
     *
     * @param prefer the variant this box shows of the variants groups it shows whole, or null
     * @param split  how the box continues on further pages, or null if it never breaks
     * @param fit    what to try when its content doesn't fit, in order (see {@link Remedy})
     */
    public record Rules(When when, Priority priority, Size size, Drop drop, Variants.Variant prefer, Split split,
                        List<Remedy> fit) {
        public static final Rules DEFAULT = new Rules(When.ALWAYS, Priority.ESSENTIAL, null, null, null, null, List.of());
    }

    /** The box that may split across pages, or null. */
    public Box splitting() {
        return boxes.stream().filter(b -> b.rules().split() != null).findFirst().orElse(null);
    }

    /** A {@code prefer:} variant name, or null. */
    static Variants.Variant prefer(YamlSpec spec) throws ConfigException {
        String name = spec.string("prefer", null);
        if (name == null) {
            return null;
        }
        Variants.Variant v = Variants.Variant.of(name);
        if (v == null) {
            throw spec.error("prefer", "\"" + name + "\" isn't one of [full, short, shortest]");
        }
        return v;
    }

    /** A rectangle on the page, in millimetres from the top-left corner. */
    public record Rect(double x, double y, double w, double h) {
    }

    /**
     * @param select     the selector: what a SELECT box shows, what EACH repeats over, or what a
     *                   LAYOUT box passes in (null: everything); null for other kinds
     * @param evaluator  {@code select}, compiled
     * @param pick       null for every match, N (1-based) for the Nth, -1 for the last
     * @param text       a TEXT box's text
     * @param source     a SOURCE box's stage
     * @param layoutName an EACH/LAYOUT box's named layout ({@value #PAGE_LAYOUT}: the file's own), or null
     * @param nested     an EACH/LAYOUT box's nested layout (boxes defined in the box itself), or null
     * @param area       the grid area the box goes in, or null
     * @param rules      when the box appears, how much it matters, its size limits, whether it can be dropped
     * @param value      a VALUE box's front matter key and its {@code lookup}, or null
     */
    public record Box(String id, String cssClass, Kind kind, String select, Evaluator evaluator, Integer pick,
                      Scope scope, As as, String text, MarkdownPipeline.Stage source, String layoutName,
                      Layout nested, Rect at, String area, boolean wrap, Rules rules, Value value) {

        public Box(String id, String cssClass, Kind kind, String select, Evaluator evaluator, Integer pick,
                   Scope scope, As as, String text, MarkdownPipeline.Stage source, String layoutName,
                   Layout nested, Rect at, String area, boolean wrap) {
            this(id, cssClass, kind, select, evaluator, pick, scope, as, text, source, layoutName, nested, at, area,
                    wrap, Rules.DEFAULT, null);
        }

        Box withRules(Rules rules) {
            return new Box(id, cssClass, kind, select, evaluator, pick, scope, as, text, source, layoutName, nested,
                    at, area, wrap, rules, value);
        }

        public boolean isRest() {
            return kind == Kind.REST;
        }

        public boolean isText() {
            return kind == Kind.TEXT;
        }

        public boolean isSource() {
            return kind == Kind.SOURCE;
        }

        /** PATH or OWN_LAYOUT: a line of text about the page's file. */
        public boolean isFileInfo() {
            return kind == Kind.PATH || kind == Kind.OWN_LAYOUT;
        }

        /** EACH or LAYOUT: the box holds inner layouts. */
        public boolean isNested() {
            return kind == Kind.EACH || kind == Kind.LAYOUT;
        }
    }

    /**
     * What a VALUE box shows.
     *
     * @param key    the front matter key, dotted for nested maps: {@code author.name}
     * @param lookup  the {@code data} entry the value is a key into, or null
     * @param heading the heading level (1 to 6) to show it as, an {@code <h1>} say; 0 to show it as it is
     */
    public record Value(String key, String lookup, int heading) {
    }

    private static final Pattern VALUE_KEY = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*");

    public static final String REST = "rest";

    /** In {@code layout:}, the file's own layout (its {@code layout:} front matter, or the default). */
    public static final String PAGE_LAYOUT = "page";

    /** The built-in layout: one unwrapped box holding the whole file. */
    public static final Layout DEFAULT = new Layout(Manifest.DEFAULT_LAYOUT, null, null, null, null, Unplaced.WARN, null, null,
            List.of(new Box("content", null, Kind.REST, null, null, null, Scope.PAGE, As.HTML, null, null, null, null,
                    null, null, false)));

    private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    private static final Pattern CLASSES = Pattern.compile("[A-Za-z_-][A-Za-z0-9_-]*(\\s+[A-Za-z_-][A-Za-z0-9_-]*)*");

    public Box rest() {
        return boxes.stream().filter(Box::isRest).findFirst().orElse(null);
    }

    /** The first box that reads the page's own file, if any; such a layout can't make a generated page. */
    public Box firstPageScopedBox() {
        return boxes.stream().filter(b -> !b.isText() && b.scope() == Scope.PAGE).findFirst().orElse(null);
    }

    /** Names of the layouts this one refers to by name, including from nested layouts ({@code page} excluded). */
    Set<String> references() {
        Set<String> out = new LinkedHashSet<>();
        for (Box b : boxes) {
            if (b.layoutName() != null && !b.layoutName().equals(PAGE_LAYOUT)) {
                out.add(b.layoutName());
            }
            if (b.nested() != null) {
                out.addAll(b.nested().references());
            }
        }
        return out;
    }

    /**
     * @param defaultPage the page size used when the layout doesn't set one, for checking {@code at}
     */
    static Layout load(Path file, PageSpec defaultPage) throws IOException {
        String fileName = file.getFileName().toString();
        String name = fileName.substring(0, fileName.lastIndexOf('.'));
        Object yaml;
        try {
            yaml = FrontMatter.parseYaml(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new ConfigException(fileName + ": invalid YAML: " + e.getMessage(), e);
        }
        return top(YamlSpec.root(yaml, fileName), name, file.toAbsolutePath().getParent(), defaultPage);
    }

    /**
     * An inline layout: one written in a markdown file's front matter as {@code layout:} followed
     * by the same keys a layout file has. It applies to that file only.
     *
     * @param yaml        the {@code layout:} value
     * @param name        the name to give it (see {@link Layouts#inlineName})
     * @param markdown    the markdown file, which {@code stylesheet:} is relative to
     * @param shown       how to refer to it in messages, e.g. {@code docs/a.md front matter layout}
     */
    static Layout inline(Object yaml, String name, Path markdown, String shown, PageSpec defaultPage)
            throws IOException {
        return fromYaml(yaml, name, markdown.toAbsolutePath().getParent(), shown, defaultPage);
    }

    /**
     * A layout from its definition (as written in a layout file or front matter, after any
     * {@code extends} has been merged in by {@link LayoutExtension}).
     *
     * @param dir   what a {@code stylesheet} is relative to
     * @param shown how to refer to it in messages
     */
    static Layout fromYaml(Object yaml, String name, Path dir, String shown, PageSpec defaultPage) throws IOException {
        return top(YamlSpec.root(yaml, shown), name, dir, defaultPage);
    }

    /** The keys only a whole layout has (description, page, class, stylesheet, css), then {@link #parse}. */
    private static Layout top(YamlSpec spec, String name, Path dir, PageSpec defaultPage) throws IOException {
        spec.string("description", null);
        YamlSpec pageSpec = spec.map("page");
        PageSpec page = pageSpec == null ? null : PageSpec.parse(pageSpec, defaultPage);
        String cssClass = classes(spec, "class");
        StringBuilder css = new StringBuilder();
        if (spec.raw(LayoutExtension.READ_STYLESHEETS) instanceof List<?> sheets) {
            for (Object sheet : sheets) {
                Map<?, ?> m = (Map<?, ?>) sheet;
                css.append(CssScoper.scope(String.valueOf(m.get("css")), ".layout-" + name, String.valueOf(m.get("name"))));
            }
        }
        String stylesheet = spec.string("stylesheet", null);
        if (stylesheet != null) {
            Path cssFile = dir.resolve(stylesheet);
            if (!Files.isRegularFile(cssFile)) {
                throw spec.error("stylesheet", "not found: " + cssFile);
            }
            css.append(CssScoper.scope(Files.readString(cssFile, StandardCharsets.UTF_8), ".layout-" + name, stylesheet));
        }
        String inlineCss = spec.string("css", null);
        if (inlineCss != null) {
            css.append(CssScoper.scope(inlineCss, ".layout-" + name, spec.child("css")));
        }
        return parse(spec, name, page, cssClass, css.isEmpty() ? null : css.toString(), page != null ? page : defaultPage,
                false);
    }

    /**
     * What a layout and a box's nested layout share: unplaced, grid and boxes.
     *
     * @param inner true for a box's nested boxes
     */
    private static Layout parse(YamlSpec spec, String name, PageSpec page, String cssClass, String css,
                                PageSpec effective, boolean inner) throws ConfigException {
        Unplaced unplaced = SortSpec.choice(Unplaced.class, spec.string("unplaced", "warn"), spec.child("unplaced"));
        YamlSpec gridSpec = spec.map("grid");
        Grid grid = gridSpec == null ? null : Grid.parse(gridSpec);
        YamlSpec stackSpec = spec.map("stack");
        Stack stack = null;
        if (stackSpec != null) {
            String gap = stackSpec.string("gap", null);
            if (gap != null) {
                PageSpec.length(gap, stackSpec.child("gap"));
            }
            stackSpec.finish();
            stack = new Stack(gap);
        }
        if (grid != null && stack != null) {
            throw new ConfigException(spec.context() + ": use either grid or stack, not both");
        }
        Variants.Variant prefer = prefer(spec);
        // A box's nested boxes share its keys: its own fit: is the box's (read with its rules), not a layout's.
        List<Remedy> fit = inner ? List.of() : Remedy.page(spec);
        Map<String, Grid> grids = new java.util.LinkedHashMap<>();
        YamlSpec gridsSpec = inner ? null : spec.map("grids");
        if (gridsSpec != null) {
            if (grid == null) {
                throw new ConfigException(gridsSpec.context() + ": grids are alternatives to the layout's grid, so it needs one");
            }
            for (String g : gridsSpec.keys()) {
                if (!ID.matcher(g).matches()) {
                    throw new ConfigException(gridsSpec.child(g) + ": \"" + g + "\" isn't a grid name");
                }
                grids.put(g, Grid.parse(gridsSpec.map(g)));
            }
        }
        List<YamlSpec> boxSpecs = spec.maps("boxes");
        spec.finish();
        if (boxSpecs.isEmpty()) {
            throw new ConfigException(spec.context() + ": missing required key \"boxes\"");
        }

        List<Box> boxes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (YamlSpec b : boxSpecs) {
            Box box = box(b, name, effective, inner);
            if (!ids.add(box.id())) {
                throw new ConfigException(b.context() + ": duplicate box id \"" + box.id() + "\"");
            }
            if (box.isRest() && boxes.stream().anyMatch(Box::isRest)) {
                throw new ConfigException(b.context() + ": only one box can select rest");
            }
            if (box.rules().split() != null) {
                for (Box other : boxes) {
                    if (other.rules().split() != null) {
                        throw new ConfigException(b.context() + ": split: box \"" + other.id()
                                + "\" already splits; only one box in a layout can split");
                    }
                }
            }
            checkArea(b, box, grid, boxes);
            if (stack != null && (box.area() != null || box.at() != null)) {
                throw new ConfigException(b.context() + ": a box in a stack can't set area or at; the stack places it");
            }
            boxes.add(box);
        }
        for (var g : grids.entrySet()) {
            for (Box box : boxes) {
                if (!g.getValue().names().contains(box.area())) {
                    throw new ConfigException(spec.child("grids") + "." + g.getKey() + ": box \"" + box.id() + "\" needs its"
                            + " area \"" + box.area() + "\" in this grid too; its areas are " + g.getValue().names());
                }
            }
        }
        for (Remedy r : fit) {
            if (r instanceof Remedy.UseGrid(String g) && !grids.containsKey(g)) {
                throw new ConfigException(spec.child("fit") + ": grid \"" + g + "\" isn't one of the layout's grids "
                        + grids.keySet());
            }
            if (r instanceof Remedy.DropBoxes && boxes.stream().noneMatch(b -> b.rules().drop() != null
                    && b.rules().drop().belowMin())) {
                throw new ConfigException(spec.child("fit") + ": drop: boxes needs a box that can be dropped"
                        + " (priority normal or optional, with drop: { below-min: true })");
            }
            if (r instanceof Remedy.UseLayout(String l) && l.equals(name)) {
                throw new ConfigException(spec.child("fit") + ": layout \"" + l + "\" is this layout");
            }
        }
        return new Layout(name, page, cssClass, grid, stack, unplaced, css, prefer, List.copyOf(boxes), fit,
                Map.copyOf(grids));
    }

    /** The grid a page uses: the layout's own, or one of its {@code grids} by name. */
    public Grid grid(String alternative) {
        return alternative == null ? grid : grids.get(alternative);
    }

    /** Fallback layouts this one names in its {@code fit:}. */
    Set<String> fallbacks() {
        Set<String> out = new LinkedHashSet<>();
        for (Remedy r : fit) {
            if (r instanceof Remedy.UseLayout(String l)) {
                out.add(l);
            }
        }
        return out;
    }

    private static void checkArea(YamlSpec b, Box box, Grid grid, List<Box> earlier) throws ConfigException {
        if (grid == null) {
            if (box.area() != null) {
                throw new ConfigException(b.context() + ": area \"" + box.area() + "\" needs a grid in the layout");
            }
            return;
        }
        if (box.area() == null) {
            throw new ConfigException(b.context() + ": in a grid layout every box needs an area; areas are " + grid.names());
        }
        if (!grid.names().contains(box.area())) {
            throw new ConfigException(b.context() + ": unknown area \"" + box.area() + "\"; areas are " + grid.names());
        }
        if (box.at() != null) {
            throw new ConfigException(b.context() + ": use either area or at, not both");
        }
        if (!box.wrap()) {
            throw new ConfigException(b.context() + ": a box in a grid area can't use wrap: false");
        }
        for (Box other : earlier) {
            if (box.area().equals(other.area())) {
                throw new ConfigException(b.context() + ": area \"" + box.area() + "\" already holds box \"" + other.id() + "\"");
            }
        }
    }

    private static Box box(YamlSpec b, String layoutName, PageSpec page, boolean inner) throws ConfigException {
        // Read the rules first: every kind of box below finishes the spec when it's done.
        Rules rules = rules(b);
        Box box = boxKind(b, layoutName, page);
        checkRules(b, box, rules, inner);
        return box.withRules(rules);
    }

    private static Rules rules(YamlSpec b) throws ConfigException {
        When when = When.ALWAYS;
        YamlSpec w = b.map("when");
        if (w != null) {
            boolean notEmpty = w.bool("not-empty", false);
            YamlSpec pageFilter = w.map("page");
            Where where = pageFilter == null ? null : Where.parse(pageFilter);
            List<String> outputs = w.strings("outputs");
            w.finish();
            when = new When(notEmpty, where, outputs);
        }
        Priority priority = SortSpec.choice(Priority.class, b.string("priority", "essential"), b.child("priority"));
        Size size = null;
        YamlSpec sz = b.map("size");
        if (sz != null) {
            Double min = sz.has("min") ? PageSpec.length(sz.string("min", null), sz.child("min")) : null;
            Double max = sz.has("max") ? PageSpec.length(sz.string("max", null), sz.child("max")) : null;
            sz.finish();
            if (min == null && max == null) {
                throw new ConfigException(sz.context() + ": give min, max or both");
            }
            if (min != null && max != null && min > max) {
                throw new ConfigException(sz.context() + ": min is larger than max");
            }
            size = new Size(min, max);
        }
        Drop drop = null;
        YamlSpec d = b.map("drop");
        if (d != null) {
            boolean belowMin = d.bool("below-min", false);
            Space space = SortSpec.choice(Space.class, d.string("space", "collapse"), d.child("space"));
            d.finish();
            drop = new Drop(belowMin, space);
        }
        return new Rules(when, priority, size, drop, prefer(b), split(b), Remedy.box(b));
    }

    private static final Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)%");

    private static Split split(YamlSpec b) throws ConfigException {
        Object raw = b.raw("split");
        if (raw == null || Boolean.FALSE.equals(raw)) {
            return null;
        }
        if (Boolean.TRUE.equals(raw)) {
            return new Split(null, null, List.of(), List.of(), null, null, null, 1, 0.5, BreakMarkers.NARROW, null, null);
        }
        YamlSpec s = b.map("split");
        String between = s.has("between") ? s.requiredString("between").strip() : null;
        Evaluator betweenEvaluator = compile(s, between, "between");
        List<String> keep = s.strings("keep-together").stream().map(String::strip).toList();
        List<Evaluator> keepEvaluators = new ArrayList<>();
        for (String k : keep) {
            keepEvaluators.add(compile(s, k, "keep-together"));
        }
        String repeat = s.has("repeat") ? s.requiredString("repeat").strip() : null;
        Evaluator repeatEvaluator = compile(s, repeat, "repeat");
        String label = s.string("label", null);
        Object units = s.raw("min-units");
        int minUnits = switch (units) {
            case null -> 1;
            case Integer n when n >= 1 -> n;
            default -> throw s.error("min-units", "expected a whole number from 1, got " + YamlSpec.describe(units));
        };
        double preferFill = 0.5;
        String fill = s.string("prefer-fill", null);
        if (fill != null) {
            var m = PERCENT.matcher(fill.strip());
            double v = m.matches() ? Double.parseDouble(m.group(1)) : -1;
            if (v < 0 || v > 100) {
                throw s.error("prefer-fill", "expected a percentage from 0% to 100%, e.g. 60%, got \"" + fill + "\"");
            }
            preferFill = v / 100;
        }
        BreakMarkers markers = SortSpec.choice(BreakMarkers.class, s.string("markers", "narrow"), s.child("markers"));
        String continueLayout = s.string("continue", null);
        if (continueLayout != null && !ID.matcher(continueLayout).matches()) {
            throw s.error("continue", "\"" + continueLayout + "\" isn't a layout name");
        }
        if (Layout.PAGE_LAYOUT.equals(continueLayout)) {
            throw s.error("continue", "\"page\" isn't a layout here; name the continuation layout");
        }
        String into = s.string("into", null);
        if (into != null && !ID.matcher(into).matches()) {
            throw s.error("into", "\"" + into + "\" isn't a box id");
        }
        s.finish();
        return new Split(between, betweenEvaluator, keep, List.copyOf(keepEvaluators), repeat, repeatEvaluator, label,
                minUnits, preferFill, markers, continueLayout, into);
    }

    /** Rules that contradict the box, or each other. */
    private static void checkRules(YamlSpec b, Box box, Rules rules, boolean inner) throws ConfigException {
        long splits = rules.fit().stream().filter(r -> r instanceof Remedy.Split).count();
        if (splits > 0 && rules.split() == null) {
            throw new ConfigException(b.context() + ": fit: split needs the box's own split: rule (where it may break)");
        }
        if (splits > 1) {
            throw new ConfigException(b.context() + ": fit: split is listed twice");
        }
        if (!rules.fit().isEmpty() && !box.wrap()) {
            throw new ConfigException(b.context() + ": fit: a box with wrap: false has no element to fit; leave wrap on");
        }
        if (rules.split() != null) {
            if (box.kind() != Kind.SELECT && box.kind() != Kind.REST && box.kind() != Kind.EACH) {
                throw new ConfigException(b.context() + ": split: a " + box.kind().name().toLowerCase().replace('_', '-')
                        + " box can't split; only select, rest and each boxes can");
            }
            if (inner) {
                throw new ConfigException(b.context() + ": split: only a page's own boxes can split, not nested boxes");
            }
        }
        if (rules.when().notEmpty() && box.kind() != Kind.SELECT && box.kind() != Kind.REST && !box.isNested()
                && box.kind() != Kind.VALUE) {
            throw new ConfigException(b.context() + ": when.not-empty doesn't apply to a " + box.kind().name().toLowerCase()
                    + " box, which is never empty");
        }
        if (rules.size() != null && box.at() != null) {
            throw new ConfigException(b.context() + ": size doesn't apply to a box with at (its size is fixed)");
        }
        if (rules.drop() != null) {
            if (rules.priority() == Priority.ESSENTIAL) {
                throw new ConfigException(b.context() + ": an essential box is never dropped; give it priority normal"
                        + " or optional to let it give way");
            }
            if (rules.drop().belowMin() && (rules.size() == null || rules.size().min() == null)) {
                throw new ConfigException(b.context() + ": drop.below-min needs a size.min");
            }
        }
    }

    private static Box boxKind(YamlSpec b, String layoutName, PageSpec page) throws ConfigException {
        String id = b.requiredString("id");
        if (!ID.matcher(id).matches()) {
            throw b.error("id", "\"" + id + "\" must start with a letter and use only letters, digits, - and _");
        }
        String cssClass = classes(b, "class");
        Rect at = rect(b, page);
        String area = b.string("area", null);
        boolean wrap = b.bool("wrap", true);
        String content = b.string("content", null);
        String valueKey = b.string("value", null);
        String text = b.string("text", null);
        String each = b.has("each") ? b.requiredString("each").strip() : null;
        String select = b.has("select") ? b.requiredString("select").strip() : null;
        String named = b.string("layout", null);
        boolean nestedBoxes = b.has("boxes");
        boolean hasPick = b.has("pick");
        boolean hasScope = b.has("scope");
        boolean hasAs = b.has("as");

        if (valueKey != null) {
            if (!VALUE_KEY.matcher(valueKey).matches()) {
                throw b.error("value", "\"" + valueKey + "\" isn't a front matter key; use names like symptom or author.name");
            }
            String lookup = b.string("lookup", null);
            if (lookup != null && !VALUE_KEY.matcher(lookup).matches()) {
                throw b.error("lookup", "\"" + lookup + "\" isn't a data name, such as levels or team.members");
            }
            if (content != null || text != null || each != null || select != null || named != null || nestedBoxes
                    || hasPick || hasScope) {
                throw new ConfigException(b.context() + ": a value box can't also set select, text, content, each,"
                        + " layout, boxes, pick or scope");
            }
            As as = SortSpec.choice(As.class, b.string("as", "html"), b.child("as"));
            if (as != As.HTML && as != As.TEXT) {
                throw b.error("as", "a value box shows html or text");
            }
            String headingText = b.string("heading", "0");
            if (!headingText.matches("[0-6]")) {
                throw b.error("heading", "expected a heading level from 1 to 6, got \"" + headingText + "\"");
            }
            b.finish();
            return new Box(id, cssClass, Kind.VALUE, null, null, null, Scope.PAGE, as, null, null, null, null, at, area,
                    wrap, Rules.DEFAULT, new Value(valueKey, lookup, Integer.parseInt(headingText)));
        }
        if (b.has("lookup")) {
            throw new ConfigException(b.context() + ": lookup only applies to a value box");
        }
        if (content != null) {
            Kind kind = switch (content) {
                case "source" -> Kind.SOURCE;
                case "path" -> Kind.PATH;
                case "layout" -> Kind.OWN_LAYOUT;
                case "layout-source" -> Kind.LAYOUT_SOURCE;
                default -> throw b.error("content", "\"" + content
                        + "\" isn't a content kind; use source, path, layout or layout-source");
            };
            if (kind != Kind.SOURCE && b.has("stage")) {
                throw new ConfigException(b.context() + ": stage only applies to content: source");
            }
            only(b, "a content box", text != null || each != null || select != null || named != null || nestedBoxes
                    || hasPick || hasScope || hasAs);
            MarkdownPipeline.Stage stage = SortSpec.choice(MarkdownPipeline.Stage.class, b.string("stage", "original"),
                    b.child("stage"));
            b.finish();
            return new Box(id, cssClass, kind, null, null, null, Scope.PAGE, As.HTML, null,
                    kind == Kind.SOURCE ? stage : null, null, null, at, area, wrap);
        }
        if (b.has("stage")) {
            throw new ConfigException(b.context() + ": stage only applies to content: source");
        }
        if (text != null) {
            only(b, "a text box", each != null || select != null || named != null || nestedBoxes || hasPick || hasScope
                    || hasAs);
            b.finish();
            return new Box(id, cssClass, Kind.TEXT, null, null, null, Scope.PAGE, As.TEXT, text, null, null, null,
                    at, area, wrap);
        }

        if (each != null || named != null || nestedBoxes) {
            if (named != null && nestedBoxes) {
                throw new ConfigException(b.context() + ": use either layout or boxes, not both");
            }
            if (named == null && !nestedBoxes) {
                throw new ConfigException(b.context() + ": each needs a layout (layout: name) or nested boxes (boxes: ...)");
            }
            if (each != null && select != null) {
                throw new ConfigException(b.context() + ": use either each or select, not both");
            }
            if (hasAs) {
                throw new ConfigException(b.context() + ": as doesn't apply to a box holding a layout");
            }
            Kind kind = each != null ? Kind.EACH : Kind.LAYOUT;
            String sel = each != null ? each : select;
            if (REST.equals(sel)) {
                throw new ConfigException(b.context() + ": rest can't be passed into a layout");
            }
            Integer pick = pick(b);
            if (pick != null && sel == null) {
                throw new ConfigException(b.context() + ": pick needs a select");
            }
            Scope scope = SortSpec.choice(Scope.class, b.string("scope", "page"), b.child("scope"));
            if (kind == Kind.LAYOUT && scope != Scope.PAGE) {
                throw new ConfigException(b.context() + ": scope " + scope.name().toLowerCase()
                        + " needs each (one inner layout per match)");
            }
            if (named != null && !ID.matcher(named).matches()) {
                throw b.error("layout", "\"" + named + "\" isn't a layout name");
            }
            Evaluator evaluator = compile(b, sel, each != null ? "each" : "select");
            Layout nested = null;
            if (nestedBoxes) {
                // The nested layout reads unplaced, grid and boxes from this box's own keys.
                nested = parse(b, layoutName + "--" + id, null, null, null, page, true);
            } else {
                if (b.has("grid") || b.has("stack") || b.has("unplaced")) {
                    throw new ConfigException(b.context() + ": grid, stack and unplaced go in the named layout, or use nested boxes");
                }
                b.finish();
            }
            return new Box(id, cssClass, kind, sel, evaluator, pick, scope, As.HTML, null, null, named, nested,
                    at, area, wrap);
        }
        if (b.has("grid") || b.has("stack") || b.has("unplaced")) {
            throw new ConfigException(b.context() + ": grid, stack and unplaced only apply to a box with nested boxes");
        }

        if (select == null) {
            throw new ConfigException(b.context() + ": missing required key \"select\" (or \"text\" for fixed text,"
                    + " content: source, or a layout)");
        }
        boolean rest = select.equals(REST);
        Integer pick = pick(b);
        Scope scope = SortSpec.choice(Scope.class, b.string("scope", "page"), b.child("scope"));
        As as = SortSpec.choice(As.class, b.string("as", "html"), b.child("as"));
        b.finish();
        if (rest && (pick != null || scope != Scope.PAGE || as != As.HTML)) {
            throw new ConfigException(b.context() + ": a rest box can't set pick, scope or as");
        }
        return new Box(id, cssClass, rest ? Kind.REST : Kind.SELECT, rest ? null : select,
                rest ? null : compile(b, select, "select"), pick, scope, as, null, null, null, null, at, area, wrap);
    }

    private static void only(YamlSpec b, String what, boolean conflict) throws ConfigException {
        if (conflict) {
            throw new ConfigException(b.context() + ": " + what + " can't also set select, text, each, layout, boxes,"
                    + " pick, scope or as");
        }
    }

    private static Evaluator compile(YamlSpec b, String selector, String key) throws ConfigException {
        if (selector == null) {
            return null;
        }
        try {
            return Selector.evaluatorOf(selector);
        } catch (RuntimeException e) {
            throw b.error(key, "invalid selector \"" + selector + "\": " + e.getMessage());
        }
    }

    private static Integer pick(YamlSpec b) throws ConfigException {
        Object v = b.raw("pick");
        return switch (v) {
            case null -> null;
            case String s when s.equals("first") -> 1;
            case String s when s.equals("last") -> -1;
            case Integer n when n >= 1 -> n;
            default -> throw b.error("pick", "expected first, last or a number from 1, got " + YamlSpec.describe(v));
        };
    }

    private static Rect rect(YamlSpec b, PageSpec page) throws ConfigException {
        YamlSpec at = b.map("at");
        if (at == null) {
            return null;
        }
        double x = PageSpec.length(at.requiredString("x"), at.child("x"));
        double y = PageSpec.length(at.requiredString("y"), at.child("y"));
        double w = PageSpec.length(at.requiredString("w"), at.child("w"));
        double h = PageSpec.length(at.requiredString("h"), at.child("h"));
        at.finish();
        if (w <= 0 || h <= 0) {
            throw new ConfigException(at.context() + ": w and h must be positive");
        }
        if (x < 0 || y < 0 || x + w > page.width() + 1e-6 || y + h > page.height() + 1e-6) {
            throw new ConfigException(at.context() + ": box (" + fmt(x) + ", " + fmt(y) + ", " + fmt(w) + " x " + fmt(h)
                    + "mm) doesn't fit on the " + fmt(page.width()) + " x " + fmt(page.height()) + "mm page");
        }
        return new Rect(x, y, w, h);
    }

    private static String classes(YamlSpec spec, String key) throws ConfigException {
        String v = spec.string(key, null);
        if (v != null && !CLASSES.matcher(v.strip()).matches()) {
            throw spec.error(key, "\"" + v + "\" isn't a list of CSS class names");
        }
        return v == null ? null : v.strip();
    }

    private static String fmt(double mm) {
        return mm == Math.rint(mm) ? String.valueOf((long) mm) : String.format("%.1f", mm);
    }
}
