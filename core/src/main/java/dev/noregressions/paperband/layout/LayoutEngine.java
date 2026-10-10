package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.CombinedAst;
import dev.noregressions.paperband.MarkdownFile;
import dev.noregressions.paperband.MarkdownPipeline;
import dev.noregressions.paperband.NodeInfo;
import dev.noregressions.paperband.Variants;
import org.commonmark.node.Node;
import org.jsoup.nodes.Element;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the final AST into pages: one per sequence item, using the item's layout.
 * <ul>
 *   <li>Each box's selector runs over its scope: the content the layout was given
 *       ({@code scope: page}: the page's file, or for an inner layout what the outer box passed
 *       in), every file in the sequence ({@code sequence}) or every source ({@code sources});
 *       {@code pick} applies per file.</li>
 *   <li>A box holding a layout fills that inner layout with its selection (or everything it was
 *       given); an {@code each} box fills one inner layout per match. Inner layouts can nest, but
 *       a layout can't contain itself, so nesting always ends.</li>
 *   <li>The {@code rest} box gets the top-level blocks of the scope that no page-scoped box took.
 *       A block that a box took only part of (a paragraph inside a list, say) stays in rest whole,
 *       and the report says so.</li>
 *   <li>Without a rest box, blocks no box placed are dropped and reported, or not, or fail the
 *       build, per the layout's {@code unplaced}.</li>
 *   <li>A box whose {@code when} conditions don't hold isn't on the page at all: no div, no
 *       warnings, and what it would have taken is left for {@code rest}.</li>
 * </ul>
 */
public final class LayoutEngine {

    /** The name of the output a build makes when the manifest declares none. */
    public static final String DEFAULT_OUTPUT = "site";

    private LayoutEngine() {
    }

    /** Checks that generated pages (no source file) only use layouts that don't read a page's own file. */
    public static void validate(ResolvedSequence sequence, Layouts layouts) throws ConfigException {
        for (ResolvedSequence.Item item : sequence.items()) {
            Layout layout = layouts.get(item.layout());
            Layout.Box box = layout.firstPageScopedBox();
            if (item.file() == null && box != null) {
                throw new ConfigException(item.context() + ": layout \"" + layout.name() + "\" can't make a generated page: box \""
                        + box.id() + "\" " + (box.isRest() ? "selects rest" : box.isSource() ? "shows the page's source"
                        : box.kind() == Layout.Kind.VALUE ? "shows a value from the page's front matter"
                        : box.isFileInfo() || box.kind() == Layout.Kind.LAYOUT_SOURCE ? "shows the page's file"
                        : "has scope: page")
                        + ", which needs a source file (use scope: sequence or sources)");
            }
        }
    }

    public static List<Page> build(ResolvedSequence sequence, CombinedAst ast, Layouts layouts, PageSpec defaultPage,
                                   RunReport report) throws ConfigException {
        return build(sequence, ast, Map.of(), layouts, defaultPage, Manifest.DEFAULT_LAYOUT, report);
    }

    /**
     * @param texts         each file's text at every stage, for source boxes
     * @param defaultLayout the layout a file without {@code layout:} front matter uses, for {@code layout: page}
     */
    public static List<Page> build(ResolvedSequence sequence, CombinedAst ast, Map<Path, MarkdownPipeline.Texts> texts,
                                   Layouts layouts, PageSpec defaultPage, String defaultLayout, RunReport report)
            throws ConfigException {
        return build(sequence, ast, texts, layouts, defaultPage, defaultLayout, DEFAULT_OUTPUT, report);
    }

    /** @param output the name of the output being built, for {@code when.outputs} */
    public static List<Page> build(ResolvedSequence sequence, CombinedAst ast, Map<Path, MarkdownPipeline.Texts> texts,
                                   Layouts layouts, PageSpec defaultPage, String defaultLayout, String output,
                                   RunReport report) throws ConfigException {
        return build(sequence, ast, texts, layouts, defaultPage, defaultLayout, output, null, report);
    }

    /**
     * @param prefer the output's preferred variant (a page's layout's {@code prefer} wins), or null
     *               for the longest present
     */
    public static List<Page> build(ResolvedSequence sequence, CombinedAst ast, Map<Path, MarkdownPipeline.Texts> texts,
                                   Layouts layouts, PageSpec defaultPage, String defaultLayout, String output,
                                   Variants.Variant prefer, RunReport report) throws ConfigException {
        return build(sequence, ast, texts, layouts, defaultPage, defaultLayout, output, prefer, false, report);
    }

    /**
     * @param paged true for an output with pages (paged-html, pdf): break markers are checked
     *              against the boxes they land in (see {@link BreakCheck})
     */
    public static List<Page> build(ResolvedSequence sequence, CombinedAst ast, Map<Path, MarkdownPipeline.Texts> texts,
                                   Layouts layouts, PageSpec defaultPage, String defaultLayout, String output,
                                   Variants.Variant prefer, boolean paged, RunReport report) throws ConfigException {
        return build(sequence, ast, texts, layouts, defaultPage, defaultLayout, output, prefer, paged, Map.of(), report);
    }

    /**
     * @param layoutFor layouts to use instead of the sequence's, by page index: a fit remedy's
     *                  fallback layout ({@code fit: [layout: NAME]})
     */
    public static List<Page> build(ResolvedSequence sequence, CombinedAst ast, Map<Path, MarkdownPipeline.Texts> texts,
                                   Layouts layouts, PageSpec defaultPage, String defaultLayout, String output,
                                   Variants.Variant prefer, boolean paged, Map<Integer, String> layoutFor,
                                   RunReport report) throws ConfigException {
        return build(sequence, ast, texts, layouts, defaultPage, defaultLayout, output, prefer, paged, layoutFor,
                Map.of(), report);
    }

    /** @param data the build's data files (as templates see them), for value boxes' {@code lookup} */
    public static List<Page> build(ResolvedSequence sequence, CombinedAst ast, Map<Path, MarkdownPipeline.Texts> texts,
                                   Layouts layouts, PageSpec defaultPage, String defaultLayout, String output,
                                   Variants.Variant prefer, boolean paged, Map<Integer, String> layoutFor,
                                   Map<String, Object> data, RunReport report) throws ConfigException {
        Map<Path, MarkdownFile> byPath = byPath(ast);
        Context ctx = context(sequence, ast, texts, layouts, defaultLayout, output, data, report);

        List<Page> pages = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int generated = 0;
        for (ResolvedSequence.Item item : sequence.items()) {
            Layout layout = layouts.get(layoutFor.getOrDefault(pages.size(), item.layout()));
            MarkdownFile file = item.file() == null ? null : byPath.get(item.file().normalize());
            if (item.file() != null && file == null) {
                throw new IllegalStateException(item.file() + " is in the sequence but not in the AST");
            }
            String where = item.file() == null ? item.context() : item.file().toString();
            // What selectors see follows the page's preference: its layout's, else the output's.
            Variants.Variant pagePrefer = layout.prefer() != null ? layout.prefer() : prefer;
            Scope scope = file == null ? new Scope(null, List.of(), pagePrefer)
                    : new Scope(file, List.of(ctx.view(file, pagePrefer).root()), pagePrefer);
            Deque<String> stack = new ArrayDeque<>(List.of(layout.name()));
            Map<Layout.Box, List<List<Node>>> units = new IdentityHashMap<>();
            List<Page.PlacedBox> placed = fill(layout, scope, ctx, stack, where, units, null);
            if (paged) {
                BreakCheck.check(layout, placed, units, pagePrefer, ctx::view, where);
            }

            String id = item.file() != null ? anchor(item.file().toString()) : anchor(layout.name() + "-" + ++generated);
            String unique = id;
            for (int n = 2; !ids.add(unique); n++) {
                unique = id + "-" + n;
            }
            PageSpec size = layout.page() != null ? layout.page() : defaultPage;
            pages.add(new Page(unique, layout, item.file(), size, placed));
        }
        return List.copyOf(pages);
    }

    /**
     * A continuation page: where a box that splits continues (see {@link Layout.Split}). It's
     * made with the continuation layout for the same file; the {@code into} box is left empty for
     * the caller to fill with the rest, and the layout's other boxes that read the page's file
     * are left out, so nothing is shown twice. Fixed text, file information and boxes that read
     * the sequence or all sources are filled as usual.
     *
     * @param page   the page that continues
     * @param layout the continuation layout's name
     * @param into   the id of the box that takes the rest
     * @param id     the new page's id
     */
    public static Page continuation(Page page, String layout, String into, String id, ResolvedSequence sequence,
                                    CombinedAst ast, Map<Path, MarkdownPipeline.Texts> texts, Layouts layouts,
                                    PageSpec defaultPage, String defaultLayout, String output, Variants.Variant prefer,
                                    Map<String, Object> data)
            throws ConfigException {
        Context ctx = context(sequence, ast, texts, layouts, defaultLayout, output, data, new RunReport());
        Layout l = layouts.get(layout);
        MarkdownFile file = page.source() == null ? null : byPath(ast).get(page.source().normalize());
        Variants.Variant pagePrefer = l.prefer() != null ? l.prefer() : prefer;
        Scope scope = file == null ? new Scope(null, List.of(), pagePrefer)
                : new Scope(file, List.of(ctx.view(file, pagePrefer).root()), pagePrefer);
        String where = page.source() == null ? page.id() : page.source().toString();
        List<Page.PlacedBox> placed = fill(l, scope, ctx, new ArrayDeque<>(List.of(l.name())), where, null, into);
        PageSpec size = l.page() != null ? l.page() : defaultPage;
        return new Page(id, l, page.source(), size, placed);
    }

    private static Map<Path, MarkdownFile> byPath(CombinedAst ast) {
        Map<Path, MarkdownFile> byPath = new LinkedHashMap<>();
        for (MarkdownFile f : ast.getFiles()) {
            byPath.put(f.getPath().normalize(), f);
        }
        return byPath;
    }

    private static Context context(ResolvedSequence sequence, CombinedAst ast, Map<Path, MarkdownPipeline.Texts> texts,
                                   Layouts layouts, String defaultLayout, String output, Map<String, Object> data,
                                   RunReport report) {
        Map<Path, MarkdownFile> byPath = byPath(ast);
        Map<Path, MarkdownPipeline.Texts> textsByPath = new LinkedHashMap<>();
        texts.forEach((p, t) -> textsByPath.put(p.normalize(), t));
        return new Context(sequence.files().stream().map(p -> byPath.get(p.normalize())).toList(),
                ast.getFiles(), new java.util.HashMap<>(), textsByPath, layouts, defaultLayout, output, data, report);
    }

    private record Context(List<MarkdownFile> sequenceFiles, List<MarkdownFile> allFiles,
                           Map<ViewKey, AstView> views, Map<Path, MarkdownPipeline.Texts> texts, Layouts layouts,
                           String defaultLayout, String output, Map<String, Object> data, RunReport report) {

        AstView view(MarkdownFile f, Variants.Variant prefer) {
            return views.computeIfAbsent(new ViewKey(f, prefer), k -> new AstView(k.file(), k.prefer()));
        }
    }

    /** One file's selector view, for one variant preference. */
    private record ViewKey(MarkdownFile file, Variants.Variant prefer) {
    }

    /**
     * What a layout is filling: elements of one file's view (none, for a generated page), and the
     * variant preference that view shows.
     */
    private record Scope(MarkdownFile file, List<Element> roots, Variants.Variant prefer) {

        Scope within(MarkdownFile f, List<Element> roots) {
            return new Scope(f, roots, prefer);
        }
    }

    /**
     * @param units      for a page's own layout, where each box's matches are recorded; null for an inner layout
     * @param continuing for a continuation page, the id of the box that takes the rest (see
     *                   {@link #continuation}); else null
     */
    private static List<Page.PlacedBox> fill(Layout layout, Scope scope, Context ctx, Deque<String> stack, String where,
                                             Map<Layout.Box, List<List<Node>>> units, String continuing)
            throws ConfigException {
        // An inner layout can be working on another file than the page's (each over the sequence, say).
        String other = scope.file() == null || scope.file().getPath().toString().equals(where) ? ""
                : " [" + scope.file().getPath() + "]";
        String in = where + other + " (layout " + String.join(" > ", stack.reversed()) + ")";
        List<Page.PlacedBox> placed = new ArrayList<>();
        Set<Node> taken = AstView.identitySet(List.of());
        int restIndex = -1;
        for (Layout.Box box : layout.boxes()) {
            if (!applies(box, scope, ctx)) {
                continue; // not on this page at all: no div, no warnings, nothing taken
            }
            if (continuing != null) {
                if (box.id().equals(continuing)) {
                    placed.add(new Page.PlacedBox(box, List.of())); // filled with the rest by the caller
                    continue;
                }
                if (readsPage(box)) {
                    continue; // already shown on the page this one continues
                }
            }
            boolean ifFound = box.rules().when().notEmpty();
            switch (box.kind()) {
                case REST -> {
                    restIndex = placed.size(); // filled in once every other box has chosen
                    placed.add(null);
                }
                case TEXT -> placed.add(new Page.PlacedBox(box, List.of()));
                case VALUE -> {
                    Page.PlacedBox v = value(box, scope.file(), ctx, in);
                    if (v != null) {
                        placed.add(v);
                    } else if (!ifFound) {
                        ctx.report().warn(in + ": box \"" + box.id() + "\": the page's front matter has no "
                                + box.value().key());
                    }
                }
                case SOURCE -> {
                    MarkdownPipeline.Texts t = ctx.texts().get(scope.file().getPath().normalize());
                    if (t == null) {
                        throw new IllegalStateException("no source text for " + scope.file().getPath());
                    }
                    placed.add(new Page.PlacedBox(box, List.of(), t.at(box.source())));
                }
                case PATH -> placed.add(new Page.PlacedBox(box, List.of(),
                        scope.file().getPath().toString().replace('\\', '/')));
                case OWN_LAYOUT -> placed.add(new Page.PlacedBox(box, List.of(), ownLayout(scope.file(), ctx)));
                case LAYOUT_SOURCE -> placed.add(new Page.PlacedBox(box, List.of(),
                        ctx.layouts().source(named(Layout.PAGE_LAYOUT, scope.file(), ctx, where).name())));
                case SELECT -> {
                    List<Node> nodes = new ArrayList<>();
                    for (MarkdownFile f : filesFor(box, scope, ctx)) {
                        AstView v = ctx.view(f, scope.prefer());
                        nodes.addAll(v.nodes(v.match(box.evaluator(), box.pick(), rootsFor(box, scope, v))));
                    }
                    if (nodes.isEmpty()) {
                        if (ifFound) {
                            break;
                        }
                        ctx.report().warn(in + ": box \"" + box.id() + "\" selected nothing (" + box.select() + ")");
                    }
                    if (box.scope() == Layout.Scope.PAGE) {
                        taken.addAll(nodes);
                    }
                    placed.add(new Page.PlacedBox(box, List.copyOf(nodes)));
                }
                case EACH -> {
                    List<Page.Nested> nested = new ArrayList<>();
                    for (MarkdownFile f : filesFor(box, scope, ctx)) {
                        AstView v = ctx.view(f, scope.prefer());
                        for (Element match : v.match(box.evaluator(), box.pick(), rootsFor(box, scope, v))) {
                            nested.add(inner(box, scope.within(f, List.of(match)), ctx, stack, where));
                            if (units != null) {
                                units.computeIfAbsent(box, k -> new ArrayList<>()).add(v.nodes(List.of(match)));
                            }
                            if (box.scope() == Layout.Scope.PAGE) {
                                taken.addAll(v.nodes(List.of(match)));
                            }
                        }
                    }
                    if (nested.isEmpty()) {
                        if (ifFound) {
                            break;
                        }
                        ctx.report().warn(in + ": box \"" + box.id() + "\" matched nothing (each: " + box.select() + ")");
                    }
                    placed.add(new Page.PlacedBox(box, List.of(), null, List.copyOf(nested)));
                }
                case LAYOUT -> {
                    AstView v = ctx.view(scope.file(), scope.prefer());
                    List<Element> roots = box.evaluator() == null ? scope.roots()
                            : v.match(box.evaluator(), box.pick(), scope.roots());
                    if (roots.isEmpty()) {
                        if (ifFound) {
                            break;
                        }
                        ctx.report().warn(in + ": box \"" + box.id() + "\" selected nothing (" + box.select() + ")");
                    }
                    Page.Nested filled = inner(box, scope.within(scope.file(), roots), ctx, stack, where);
                    // The content passed in is the box's, unless its layout reads none of it: a panel of
                    // values or fixed text leaves the page's content for the boxes after it.
                    if (box.evaluator() != null || readsContent(filled.layout())) {
                        taken.addAll(v.nodes(roots));
                    }
                    placed.add(new Page.PlacedBox(box, List.of(), null, List.of(filled)));
                }
            }
        }

        // An inner layout that reads none of what it's given (values, fixed text) isn't placing content,
        // so what it leaves is no news: the page's own boxes place it.
        boolean placesContent = stack.size() == 1 || readsContent(layout);
        if (scope.file() != null && continuing == null && placesContent) {
            List<Node> left = new ArrayList<>();
            List<Node> partial = new ArrayList<>();
            for (Node block : ctx.view(scope.file(), scope.prefer()).nodes(scope.roots())) {
                if (taken.contains(block)) {
                    continue;
                }
                left.add(block);
                if (taken.stream().anyMatch(n -> isAncestor(block, n))) {
                    partial.add(block);
                }
            }
            Layout.Box rest = restIndex >= 0 ? layout.rest() : null;
            if (rest != null && left.isEmpty() && rest.rules().when().notEmpty()) {
                placed.remove(restIndex);
            } else if (rest != null) {
                placed.set(restIndex, new Page.PlacedBox(rest, List.copyOf(left)));
                if (!partial.isEmpty()) {
                    ctx.report().warn(in + ": rest box \"" + rest.id()
                            + "\" includes blocks that other boxes took part of, so that part appears twice: "
                            + describe(partial));
                }
            } else if (!left.isEmpty()) {
                String message = in + ": " + left.size()
                        + " block(s) not placed by any box (add a box with select: rest to keep them): " + describe(left);
                switch (layout.unplaced()) {
                    case WARN -> ctx.report().warn(message);
                    case ERROR -> throw new ConfigException(message);
                    case IGNORE -> {
                    }
                }
            }
        }
        // How each box renders the variants groups it shows whole: its own prefer, else its layout's, else the scope's.
        List<Page.PlacedBox> out = new ArrayList<>();
        for (Page.PlacedBox p : placed) {
            Variants.Variant boxPrefer = p.box().rules().prefer() != null ? p.box().rules().prefer()
                    : layout.prefer() != null ? layout.prefer() : scope.prefer();
            out.add(p.preferring(boxPrefer));
        }
        return out;
    }

    /** True if a layout (or one inside it) has a box that shows content passed to it. */
    private static boolean readsContent(Layout layout) {
        for (Layout.Box b : layout.boxes()) {
            switch (b.kind()) {
                case SELECT, EACH -> {
                    if (b.scope() == Layout.Scope.PAGE) {
                        return true;
                    }
                }
                case REST -> {
                    return true;
                }
                case LAYOUT -> {
                    if (b.evaluator() != null || b.nested() == null || readsContent(b.nested())) {
                        return true; // a named inner layout is taken to read what it's given
                    }
                }
                default -> {
                }
            }
        }
        return false;
    }

    /** True for a box that shows content of the page's own file (on a continuation page, already shown). */
    private static boolean readsPage(Layout.Box box) {
        return switch (box.kind()) {
            case REST, SOURCE, LAYOUT, VALUE -> true;
            case SELECT, EACH -> box.scope() == Layout.Scope.PAGE;
            case TEXT, PATH, OWN_LAYOUT, LAYOUT_SOURCE -> false;
        };
    }

    /** An id-like value becomes a class on its box: {@code level: wont-start} gives {@code level-wont-start}. */
    private static final java.util.regex.Pattern VALUE_ID = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]*");

    /**
     * A value box filled from {@code file}'s front matter: the value parsed as markdown (its
     * markers too, reported against the file), or null if the file has no such value.
     */
    private static Page.PlacedBox value(Layout.Box box, MarkdownFile file, Context ctx, String in)
            throws ConfigException {
        Layout.Value spec = box.value();
        Object v = lookup(file.getFrontMatter(), spec.key());
        if (v == null) {
            return null;
        }
        String id = v instanceof String s && VALUE_ID.matcher(s).matches() ? s : null;
        if (spec.lookup() != null) {
            Object table = lookup(ctx.data(), spec.lookup());
            if (!(table instanceof Map<?, ?> entries)) {
                throw new ConfigException(in + ": box \"" + box.id() + "\": lookup: there's no data." + spec.lookup()
                        + " (a data file such as data/" + spec.lookup().replace('.', '/') + ".yml)");
            }
            Object entry = entries.get(String.valueOf(v));
            if (entry == null) {
                throw new ConfigException(file.getPath() + ": " + spec.key() + " \"" + v + "\" isn't in data."
                        + spec.lookup() + "; it has " + entries.keySet());
            }
            v = entry instanceof Map<?, ?> m ? m.get("name") : entry;
            if (v == null) {
                throw new ConfigException(in + ": box \"" + box.id() + "\": data." + spec.lookup() + "." + id
                        + " has no name to show");
            }
        }
        String markdown = switch (v) {
            case List<?> items -> String.join("\n", items.stream().map(i -> "- " + i).toList());
            default -> String.valueOf(v);
        };
        try {
            MarkdownFile parsed = new dev.noregressions.paperband.CombinedAst().add(Path.of(file.getPath() + " front matter " + spec.key()),
                    markdown);
            List<Node> nodes = new ArrayList<>();
            for (Node n = parsed.getFirstChild(); n != null; n = n.getNext()) {
                nodes.add(n);
            }
            return new Page.PlacedBox(box, List.copyOf(nodes), id == null ? null : leaf(spec.key()) + "-" + id);
        } catch (ConfigException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new ConfigException(e.getMessage(), e);
        }
    }

    /** A value at a dotted path in nested maps, or null. */
    private static Object lookup(Map<String, ?> map, String path) {
        Object v = map;
        for (String part : path.split("\\.")) {
            if (!(v instanceof Map<?, ?> m)) {
                return null;
            }
            v = m.get(part);
        }
        return v;
    }

    private static String leaf(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    /** A box's page and output conditions; {@code not-empty} is checked once it has selected. */
    private static boolean applies(Layout.Box box, Scope scope, Context ctx) throws ConfigException {
        Layout.When when = box.rules().when();
        if (!when.outputs().isEmpty() && !when.outputs().contains(ctx.output())) {
            return false;
        }
        if (when.page() != null) {
            Map<String, Object> fm = scope.file() == null ? Map.of() : scope.file().getFrontMatter();
            String file = scope.file() == null ? "a generated page" : scope.file().getPath().toString();
            return when.page().test(fm, file);
        }
        return true;
    }

    /** Fills a box's inner layout over {@code scope}. */
    private static Page.Nested inner(Layout.Box box, Scope scope, Context ctx, Deque<String> stack, String where)
            throws ConfigException {
        Layout layout = box.nested() != null ? box.nested() : named(box.layoutName(), scope.file(), ctx, where);
        if (stack.contains(layout.name())) {
            List<String> loop = new ArrayList<>(stack.reversed());
            loop.add(layout.name());
            throw new ConfigException(where + ": layouts include each other in a loop: " + String.join(" > ", loop));
        }
        Layout.Box splitting = layout.splitting();
        if (splitting != null) {
            throw new ConfigException(where + ": layout \"" + layout.name() + "\" is used inside box \"" + box.id()
                    + "\", but its box \"" + splitting.id() + "\" splits; only a page's own boxes can split");
        }
        stack.push(layout.name());
        try {
            List<Node> match = scope.file() == null ? List.of() : ctx.view(scope.file(), scope.prefer()).nodes(scope.roots());
            return new Page.Nested(layout, List.copyOf(fill(layout, scope, ctx, stack, where, null, null)), match);
        } finally {
            stack.pop();
        }
    }

    /** A layout by name; {@code page} is the file's own (its {@code layout:} front matter, or the default). */
    private static Layout named(String name, MarkdownFile file, Context ctx, String where) throws ConfigException {
        if (name.equals(Layout.PAGE_LAYOUT)) {
            name = ownLayout(file, ctx);
        }
        if (!ctx.layouts().names().contains(name)) {
            throw new ConfigException(where + ": unknown layout \"" + name + "\"; available: "
                    + ctx.layouts().names().stream().sorted().toList());
        }
        return ctx.layouts().get(name);
    }

    /** The layout a file asks for itself: its {@code layout:} front matter (a name, or an inline layout), or the default. */
    private static String ownLayout(MarkdownFile file, Context ctx) throws ConfigException {
        return switch (file.getFrontMatter().get("layout")) {
            case null -> ctx.defaultLayout();
            case String name -> name;
            case Map<?, ?> definition -> Layouts.inlineName(file.getPath());
            default -> throw new ConfigException(file.getPath() + ": front matter layout must be a layout name or"
                    + " a layout definition");
        };
    }

    private static List<MarkdownFile> filesFor(Layout.Box box, Scope scope, Context ctx) {
        return switch (box.scope()) {
            case PAGE -> List.of(scope.file());
            case SEQUENCE -> ctx.sequenceFiles();
            case SOURCES -> ctx.allFiles();
        };
    }

    private static List<Element> rootsFor(Layout.Box box, Scope scope, AstView view) {
        return box.scope() == Layout.Scope.PAGE ? scope.roots() : List.of(view.root());
    }

    private static boolean isAncestor(Node ancestor, Node node) {
        for (Node p = node.getParent(); p != null; p = p.getParent()) {
            if (p == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static String describe(List<Node> nodes) {
        List<String> out = new ArrayList<>();
        for (Node n : nodes) {
            int line = NodeInfo.line(n);
            out.add(NodeInfo.name(n) + (line > 0 ? " (line " + line + ")" : ""));
        }
        return String.join(", ", out);
    }

    /** {@code docs/Intro.md} → {@code docs-intro-md}. */
    public static String anchor(String text) {
        String a = text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return a.isEmpty() ? "page" : a;
    }
}
