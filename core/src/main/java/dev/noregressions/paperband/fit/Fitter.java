package dev.noregressions.paperband.fit;

import dev.noregressions.paperband.MarkdownFile;
import dev.noregressions.paperband.Variants;
import dev.noregressions.paperband.browser.Browser;
import dev.noregressions.paperband.layout.Layout;
import dev.noregressions.paperband.layout.Page;
import dev.noregressions.paperband.layout.Remedy;
import dev.noregressions.paperband.layout.Splitting;
import org.commonmark.node.Node;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Makes an output fit: renders it, measures it in the browser ({@link FitCheck}), and while a
 * page has problems, tries the next remedy its layouts list (see {@link Remedy}), then renders
 * and measures again.
 * <ol>
 *   <li>A box that doesn't fit tries its own {@code fit:} remedies in order; what one did stays
 *       done while the next is tried. {@code shrink} and {@code truncate} (without {@code lines})
 *       search for the largest size that fits, one render per step, every searching box at once.</li>
 *   <li>When none of its boxes has anything left to try, the page tries its layout's {@code fit:}
 *       remedies: drop boxes, another grid, another layout (whose own remedies then apply).</li>
 *   <li>When nothing is left, the problems stay, with what was tried, and the build fails.</li>
 * </ol>
 * Lower-priority boxes already give way to higher ones in the paged layout itself (their grid
 * rows and stack slots share what's left, down to their {@code size.min}), so that needs no step
 * here. Style remedies become CSS in the output ({@code <style id="paperband-fit">}), so the paged
 * HTML shows what was measured and printed; the others change what's on the page.
 */
public final class Fitter {

    /** Lays the output out, with fallback layouts by page index; and makes continuation pages. */
    public interface Layouter {
        List<Page> layOut(Map<Integer, String> layoutFor) throws IOException;

        /** A page where {@code page}'s splitting box continues (see {@link dev.noregressions.paperband.layout.LayoutEngine#continuation}). */
        Page continuation(Page page, String layout, String into, String id) throws IOException;
    }

    /** Renders pages as paged HTML, with the remedies' CSS. */
    public interface Renderer {
        String render(List<Page> pages, String fitCss);
    }

    /**
     * @param pages     the pages as they finally are
     * @param html      the paged HTML that was measured (and printed)
     * @param rendering its measurement, and the PDF when printing
     * @param problems  what still doesn't fit, each with what was tried; empty when everything fits
     * @param remedies  what was done, page by page, for the report
     */
    public record Result(List<Page> pages, String html, Browser.Rendering rendering, List<Problem> problems,
                         List<String> remedies) {
    }

    /** Enough for every split, search and remedy in a long output; a safety net, not a tuning knob. */
    static final int MAX_RENDERS = 250;

    private final Layouter layouter;
    private final Renderer renderer;
    private final Browser browser;
    private final Path dir;
    private final Map<Integer, String> layoutFor = new HashMap<>();
    private final List<PageFit> fits = new ArrayList<>();
    private List<Page> base;

    private Fitter(Layouter layouter, Renderer renderer, Browser browser, Path dir) {
        this.layouter = layouter;
        this.renderer = renderer;
        this.browser = browser;
        this.dir = dir;
    }

    /**
     * @param dir   what the document's relative links resolve against
     * @param print also print the final pages to PDF
     */
    public static Result fit(Layouter layouter, Renderer renderer, Browser browser, Path dir, boolean print)
            throws IOException {
        return new Fitter(layouter, renderer, browser, dir).run(print);
    }

    private Result run(boolean print) throws IOException {
        base = layouter.layOut(layoutFor);
        for (int i = 0; i < base.size(); i++) {
            fits.add(new PageFit(i));
        }
        List<Page> pages;
        String html;
        Browser.Rendering rendering;
        List<Problem> problems;
        int renders = 0;
        while (true) {
            pages = current();
            html = renderer.render(pages, css(pages));
            rendering = browser.render(html, dir, false);
            problems = FitCheck.check(pages, rendering.measurement());
            renders++;
            if (!next(pages, problems, rendering.measurement()) || renders >= MAX_RENDERS) {
                break;
            }
        }
        if (print) {
            rendering = browser.render(html, dir, true);
        }
        return new Result(pages, html, rendering, withTried(pages, problems), applied(pages));
    }

    /** One round of changes after a measurement; false when there's nothing more to try. */
    private boolean next(List<Page> pages, List<Problem> problems, Measurement measurement) throws IOException {
        Map<String, Measurement.Sheet> sheets = new HashMap<>();
        measurement.pages().forEach(sh -> sheets.put(sh.id(), sh));
        Map<String, List<Problem>> byPage = new HashMap<>();
        for (Problem p : problems) {
            if (p.page() != null) {
                byPage.computeIfAbsent(p.page(), k -> new ArrayList<>()).add(p);
            }
        }
        boolean again = false;
        boolean relayout = false;
        List<PageFit> added = new ArrayList<>();
        for (int at = 0; at < fits.size(); at++) {
            PageFit pf = fits.get(at);
            if (pf.gone) {
                continue;
            }
            Page page = pages.get(at);
            List<Problem> ps = byPage.getOrDefault(page.id(), List.of());
            Measurement.Sheet sheet = sheets.get(page.id());
            boolean searching = false;
            for (BoxFit bf : pf.boxes.values()) {
                if (bf.search != null && !bf.search.done) {
                    bf.search.record(ps.stream().noneMatch(p -> bf.path.equals(p.box())));
                    if (bf.search.done && !bf.search.fits) {
                        continue; // even its limit didn't fit: it stays at that, as just measured
                    }
                    // This measurement was of the candidate just tried; the next candidate, or the
                    // value the search settled on, is measured before anything else is decided.
                    again = true;
                    searching = true;
                }
            }
            List<Problem> fixable = ps.stream().filter(Problem::fixable).toList();
            if (searching) {
                continue;
            }
            if (pf.split != null && !pf.split.settled) {
                SplitState sp = pf.split;
                if (!sp.engaged) {
                    Measurement.Box mb = sp.measured(sheet);
                    boolean force = sp.forceAhead() != null;
                    boolean runsOver = mb != null && !fixable.isEmpty() && sp.runsOver(page, sheet, mb) > FitCheck.TOLERANCE;
                    if (force || runsOver) {
                        // First the remedies listed before split (all of them, if it isn't listed)...
                        BoxFit bf = force ? null : pf.box(page, sp.boxId);
                        if (bf != null && bf.advance()) {
                            again = true;
                            continue;
                        }
                        // ...then, if they weren't enough, undo them and split at the box's normal size.
                        sp.engaged = true;
                        BoxFit undone = pf.boxes.remove(sp.boxId);
                        if (undone != null && !undone.tried.isEmpty()) {
                            undone.tried.forEach(t -> pf.boxTriedBefore.add("box " + undone.path + ": " + t
                                    + " (not enough, so undone and the box split)"));
                            again = true;
                            continue; // measured again as it was before deciding where to break
                        }
                    } else if (fixable.isEmpty()) {
                        sp.settled = true;
                    }
                    // Anything else (a line too wide, say) is for the remedies below.
                }
                if (sp.engaged) {
                    again |= sp.decide(page, ps, sheet, added);
                    if (!sp.settled) {
                        continue;
                    }
                }
            }
            if (fixable.isEmpty()) {
                continue;
            }
            boolean advanced = false;
            for (String path : fixable.stream().map(Problem::box).filter(b -> b != null).distinct().toList()) {
                BoxFit bf = pf.box(page, path);
                if (bf != null && bf.advance()) {
                    advanced = true;
                }
            }
            if (!advanced) {
                PageStep step = pf.advance(page);
                advanced = step != PageStep.NONE;
                relayout |= step == PageStep.RELAYOUT;
            }
            again |= advanced;
        }
        for (PageFit c : added) {
            if (!c.gone && !c.parent.gone) {
                fits.add(fits.indexOf(c.parent) + 1, c);
            }
        }
        if (fits.removeIf(f -> f.gone)) {
            again = true;
        }
        if (relayout) {
            base = layouter.layOut(layoutFor);
        }
        return again;
    }

    /** The pages with every page's and box's Java remedies applied. */
    private List<Page> current() {
        List<Page> out = new ArrayList<>();
        for (PageFit pf : fits) {
            Page page = pf.basePage();
            if (pf.split == null && !pf.splitChecked) {
                pf.splitChecked = true;
                pf.split = splitStateOf(pf, page);
            }
            if (pf.split != null) {
                page = pf.split.apply(page);
            }
            List<Page.PlacedBox> boxes = page.boxes();
            for (BoxFit bf : pf.boxes.values()) {
                if (bf.variant != null) {
                    boxes = update(boxes, bf.path, b -> b.preferring(bf.variant));
                }
                if (bf.omitted > 0) {
                    Set<Node> omit = Collections.newSetFromMap(new IdentityHashMap<>());
                    omit.addAll(bf.optional.subList(0, bf.omitted));
                    boxes = update(boxes, bf.path, b -> b.omitting(omit));
                }
            }
            boxes = boxes.stream().filter(b -> !pf.collapsed.contains(b.box().id())).toList();
            out.add(page.withBoxes(boxes).withGrid(pf.grid));
        }
        return out;
    }

    /** The CSS for the style remedies: text size, spacing, truncation, kept space of dropped boxes. */
    private String css(List<Page> pages) {
        StringBuilder sb = new StringBuilder();
        for (int at = 0; at < fits.size(); at++) {
            PageFit pf = fits.get(at);
            String page = "section[id=\"" + cssString(pages.get(at).id()) + "\"]";
            for (String id : pf.hidden) {
                sb.append(page).append(" > [data-box=\"").append(cssString(id)).append("\"] { visibility: hidden; }\n");
            }
            for (BoxFit bf : pf.boxes.values()) {
                String box = page + " [data-box=\"" + cssString(bf.path) + "\"]";
                double scale = bf.scale();
                if (scale < 1) {
                    // Zooming the box's contents (not the box) makes the text smaller and lets it reflow in the box's width.
                    sb.append(bf.zoomsChildren() ? box + " > * { zoom: " + num(scale) + "; }\n"
                            : box + " { font-size: " + num(scale * 100) + "%; }\n");
                }
                if (bf.tighten != null) {
                    sb.append(box).append(", ").append(box).append(" * { line-height: ").append(num(bf.tighten.lineHeight()))
                            .append(" !important; }\n")
                            .append(box).append(" > * { margin-top: 0 !important; margin-bottom: 0 !important; }\n")
                            .append(box).append(" > * + * { margin-top: ").append(bf.tighten.gap()).append(" !important; }\n");
                }
                Integer lines = bf.lines();
                if (lines != null) {
                    sb.append(box).append(" { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: ")
                            .append(lines).append("; line-clamp: ").append(lines).append("; overflow: hidden; }\n");
                }
            }
        }
        return sb.toString();
    }

    private List<Problem> withTried(List<Page> pages, List<Problem> problems) {
        Map<String, List<String>> tried = new HashMap<>();
        for (int at = 0; at < fits.size(); at++) {
            tried.put(pages.get(at).id(), fits.get(at).tried());
        }
        return problems.stream().map(p -> p.page() == null ? p : p.withTried(tried.getOrDefault(p.page(), List.of())))
                .toList();
    }

    private List<String> applied(List<Page> pages) {
        List<String> out = new ArrayList<>();
        for (int at = 0; at < fits.size(); at++) {
            PageFit pf = fits.get(at);
            Page page = pages.get(at);
            String where = "page " + page.id() + (page.source() == null ? "" : " (" + page.source().toString()
                    .replace('\\', '/') + ")");
            List<String> pageDone = new ArrayList<>(pf.done);
            if (pf.split != null && pf.split.to != null) {
                pageDone.add(pf.split.describe());
            }
            if (!pageDone.isEmpty()) {
                out.add(where + ": " + String.join(", ", pageDone));
            }
            for (BoxFit bf : pf.boxes.values()) {
                List<String> done = bf.done();
                if (!done.isEmpty()) {
                    out.add(where + ", box " + bf.path + ": " + String.join(", ", done));
                }
            }
        }
        return out;
    }

    // --- one page's remedies ---------------------------------------------------------------

    private enum PageStep { NONE, CHANGED, RELAYOUT }

    private final class PageFit {
        final int index;
        final PageFit parent;    // for a continuation page, the page it continues
        final Page continuationBase;
        SplitState split;
        boolean splitChecked;
        boolean gone;            // a continuation page that's no longer needed
        final Map<String, BoxFit> boxes = new LinkedHashMap<>();
        final Set<String> collapsed = new LinkedHashSet<>();
        final Set<String> hidden = new LinkedHashSet<>();
        final Set<String> layoutsTried = new LinkedHashSet<>();
        final List<String> pageTried = new ArrayList<>();
        final List<String> boxTriedBefore = new ArrayList<>(); // from layouts the page has moved on from
        final List<String> done = new ArrayList<>();
        String grid;
        int remedy = -1;
        List<Layout.Box> droppable;
        int droppedCount;

        PageFit(int index) {
            this.index = index;
            this.parent = null;
            this.continuationBase = null;
        }

        PageFit(PageFit parent, Page continuationBase) {
            this.index = parent.index;
            this.parent = parent;
            this.continuationBase = continuationBase;
        }

        /** The page as laid out, before any remedy or split. */
        Page basePage() {
            return continuationBase != null ? continuationBase : base.get(index);
        }

        BoxFit box(Page page, String path) {
            BoxFit bf = boxes.get(path);
            if (bf == null) {
                Page.PlacedBox placed = find(page.boxes(), path);
                if (placed == null || placed.box().rules().fit().isEmpty()) {
                    return null;
                }
                List<Remedy> fit = placed.box().rules().fit();
                int stopAt = fit.size();
                if (split != null && !split.engaged && path.equals(split.boxId)) {
                    // Before it splits, a box only tries the remedies listed before split.
                    for (int i = 0; i < fit.size(); i++) {
                        if (fit.get(i) instanceof Remedy.Split) {
                            stopAt = i;
                            break;
                        }
                    }
                }
                bf = new BoxFit(path, placed, stopAt);
                boxes.put(path, bf);
            }
            return bf;
        }

        Layout layout() {
            return basePage().layout();
        }

        PageStep advance(Page page) {
            List<Remedy> fit = layout().fit();
            while (true) {
                if (remedy >= 0 && remedy < fit.size() && fit.get(remedy) instanceof Remedy.DropBoxes && dropNext()) {
                    return PageStep.CHANGED;
                }
                remedy++;
                if (remedy >= fit.size()) {
                    return PageStep.NONE;
                }
                Remedy r = fit.get(remedy);
                switch (r) {
                    case Remedy.DropBoxes d -> {
                        List<Layout.Box> candidates = page.boxes().stream().map(Page.PlacedBox::box)
                                .filter(b -> b.rules().drop() != null && b.rules().drop().belowMin()).toList();
                        // Optional before normal; within each, the later box in the layout first.
                        List<Layout.Box> ordered = new ArrayList<>();
                        for (Layout.Priority p : List.of(Layout.Priority.OPTIONAL, Layout.Priority.NORMAL)) {
                            List<Layout.Box> group = new ArrayList<>(candidates.stream().filter(b -> b.rules().priority() == p).toList());
                            Collections.reverse(group);
                            ordered.addAll(group);
                        }
                        droppable = ordered;
                        pageTried.add(r.describe());
                        if (dropNext()) {
                            return PageStep.CHANGED;
                        }
                    }
                    case Remedy.UseGrid(String name) -> {
                        pageTried.add(r.describe());
                        grid = name;
                        done.add("grid " + name);
                        return PageStep.CHANGED;
                    }
                    case Remedy.UseLayout(String name) -> {
                        if (layoutsTried.contains(name) || name.equals(layout().name()) || parent != null) {
                            continue; // a continuation page keeps its continuation layout
                        }
                        layoutsTried.add(layout().name());
                        layoutsTried.add(name);
                        pageTried.add(r.describe());
                        for (BoxFit bf : boxes.values()) {
                            bf.tried.forEach(t -> boxTriedBefore.add("box " + bf.path + ": " + t));
                        }
                        // A new layout starts afresh: its own boxes and remedies.
                        boxes.clear();
                        collapsed.clear();
                        hidden.clear();
                        grid = null;
                        remedy = -1;
                        droppedCount = 0;
                        done.clear();
                        done.add("layout " + name);
                        layoutFor.put(index, name);
                        // Its split starts again in the new layout.
                        removeContinuations(this);
                        split = null;
                        splitChecked = false;
                        return PageStep.RELAYOUT;
                    }
                    default -> {
                    }
                }
            }
        }

        private boolean dropNext() {
            if (droppable == null || droppedCount >= droppable.size()) {
                return false;
            }
            Layout.Box b = droppable.get(droppedCount++);
            if (b.rules().drop().space() == Layout.Space.LEAVE) {
                hidden.add(b.id());
            } else {
                collapsed.add(b.id());
            }
            done.add("dropped box " + b.id());
            return true;
        }

        List<String> tried() {
            List<String> out = new ArrayList<>(boxTriedBefore);
            for (BoxFit bf : boxes.values()) {
                bf.tried.forEach(t -> out.add("box " + bf.path + ": " + t));
            }
            pageTried.forEach(t -> out.add("page: " + t));
            return out;
        }
    }

    // --- splitting ------------------------------------------------------------------------------

    /** Takes a page's continuations (and theirs) out; they go from {@link #fits} after the round. */
    private void removeContinuations(PageFit pf) {
        if (pf.split != null && pf.split.next != null) {
            PageFit next = pf.split.next;
            removeContinuations(next);
            next.gone = true;
            pf.split.next = null;
        }
    }

    /**
     * One page's part of a box that splits: units {@code from} up to {@code to} of the box's
     * content, the rest on a continuation page ({@code next}), which may split again.
     */
    private final class SplitState {
        final PageFit owner;
        final String boxId;             // the box on this page: the splitting box, or a continuation's into box
        final Layout.Split rule;
        final String continueLayout;
        final String into;
        final Page.PlacedBox source;    // the box with all its content, as first laid out
        final List<Splitting.Unit> units;
        final int from;
        Integer to;                     // exclusive; null while everything from `from` on is here
        boolean settled;
        boolean engaged;                // splitting; before that, the box tries its remedies
        PageFit next;

        SplitState(PageFit owner, String boxId, Layout.Split rule, String continueLayout, String into,
                   Page.PlacedBox source, List<Splitting.Unit> units, int from) {
            this.owner = owner;
            this.boxId = boxId;
            this.rule = rule;
            this.continueLayout = continueLayout;
            this.into = into;
            this.source = source;
            this.units = units;
            this.from = from;
            this.engaged = from > 0; // a continuation is already part of a split
        }

        /** What was measured of this box on its page. */
        Measurement.Box measured(Measurement.Sheet sheet) {
            return sheet == null ? null : sheet.boxes().stream().filter(b -> b.path().equals(boxId)).findFirst().orElse(null);
        }

        /** How many units after the first a {@code {! break=force}} comes, or null. */
        Integer forceAhead() {
            for (int i = from + 1; i < units.size(); i++) {
                if (units.get(i).marker() == dev.noregressions.paperband.Breaks.Break.FORCE && units.get(i).breakable()) {
                    return i - from;
                }
            }
            return null;
        }


        private int end() {
            return to == null ? units.size() : to;
        }

        /** The page with this part of the box in it, its unit starts marked for measuring. */
        Page apply(Page page) {
            List<Splitting.Unit> here = units.subList(from, end());
            List<Page.PlacedBox> boxes = new ArrayList<>();
            for (Page.PlacedBox b : page.boxes()) {
                if (!b.box().id().equals(boxId)) {
                    boxes.add(b);
                    continue;
                }
                Node repeat = Splitting.repeat(source, rule, units, from);
                Page.Part part;
                if (source.box().kind() == Layout.Kind.EACH) {
                    List<Page.Nested> items = here.stream().map(u -> source.nested().get(u.item())).toList();
                    part = new Page.Part(Set.of(), repeat, repeat == null ? null : rule.label());
                    boxes.add(b.splitAs(List.of(), items, part));
                } else {
                    Set<Node> starts = Collections.newSetFromMap(new IdentityHashMap<>());
                    List<Node> nodes = new ArrayList<>();
                    for (Splitting.Unit u : here) {
                        starts.add(u.nodes().getFirst());
                        nodes.addAll(u.nodes());
                    }
                    part = new Page.Part(starts, repeat, repeat == null || rule.label() == null ? null : rule.label());
                    boxes.add(b.splitAs(nodes, List.of(), part));
                }
            }
            return page.withBoxes(boxes);
        }

        /**
         * After a measurement: settles, or moves the break. Returns true when something changed.
         *
         * @param added where a new continuation page goes, to join the pages after this round
         */
        boolean decide(Page page, List<Problem> problems, Measurement.Sheet sheet, List<PageFit> added)
                throws IOException {
            List<Problem> fixable = problems.stream().filter(Problem::fixable).toList();
            int here = end() - from;
            Integer force = forceAhead();
            Measurement.Box mb = measured(sheet);
            // Splitting only helps when content runs over the bottom; other problems are for the remedies.
            double needed = mb == null ? 0 : runsOver(page, sheet, mb);
            if (fixable.isEmpty() || needed <= FitCheck.TOLERANCE) {
                if (to == null && force != null) {
                    return breakAt(force, page, added); // {! break=force}: even with room left
                }
                settled = true;
                return false;
            }
            if (here <= 1) {
                settled = true;
                owner.pageTried.add("split: the unit at line " + units.get(from).line() + " doesn't fit on a page by itself");
                return false;
            }
            int kMax = here - 1;
            double limit = 0;
            List<Double> tops = mb == null ? List.of() : mb.units();
            if (mb != null) {
                limit = mb.content() - needed - 0.5;
                if (tops.size() == here) {
                    kMax = 0;
                    for (int j = 1; j <= here; j++) {
                        double endOfJ = j < here ? tops.get(j) : mb.content();
                        if (endOfJ <= limit) {
                            kMax = j;
                        }
                    }
                }
            }
            kMax = Math.min(kMax, here - 1);
            if (to != null) {
                kMax = Math.min(kMax, to - from - 1); // what was tried didn't fit
            }
            int min = rule.minUnits();
            List<Integer> candidates = new ArrayList<>();
            for (int k = 1; k < here + (to == null ? 0 : units.size() - end()); k++) {
                if (from + k < units.size() && units.get(from + k).breakable() && k >= min
                        && units.size() - from - k >= min) {
                    candidates.add(k);
                }
            }
            if (candidates.isEmpty()) {
                for (int k = 1; from + k < units.size(); k++) {
                    if (units.get(from + k).breakable()) {
                        candidates.add(k); // min-units can't hold everywhere: break anyway
                    }
                }
            }
            if (candidates.isEmpty()) {
                settled = true;
                owner.pageTried.add("split: there's no point where the box may break");
                return false;
            }
            final int max = kMax;
            List<Integer> within = candidates.stream().filter(k -> k <= max).toList();
            int k;
            if (force != null && force <= kMax && candidates.contains(force)) {
                k = force;
            } else if (!within.isEmpty()) {
                k = within.getLast();
                for (int i = within.size() - 1; i >= 0; i--) {
                    int c = within.get(i);
                    if (units.get(from + c).marker() == dev.noregressions.paperband.Breaks.Break.PREFER) {
                        double top = tops.size() == here && c < tops.size() ? tops.get(c) : (double) c / here * limit;
                        if (limit > 0 && top / limit >= rule.preferFill()) {
                            k = c;
                        }
                        break;
                    }
                }
            } else {
                k = candidates.getFirst(); // even the first break point leaves too much: remedies take it from here
                if (to != null && to - from == k) {
                    settled = true;
                    return false;
                }
            }
            return breakAt(k, page, added);
        }

        /**
         * How far the page's content runs over at the bottom: the box's own overflow, or on a page
         * with a fixed shape, how far its boxes reach past the bottom margin.
         */
        private double runsOver(Page page, Measurement.Sheet sheet, Measurement.Box mb) {
            double over = mb.overflowY();
            if (page.layout().fixed()) {
                double bottom = sheet.height() - sheet.margin()[2];
                for (Measurement.Box b : sheet.boxes()) {
                    if (!b.path().contains("/")) {
                        over = Math.max(over, b.y() + b.h() - bottom);
                    }
                }
                over = Math.max(over, sheet.overflowY());
            }
            return over;
        }

        /** Shows {@code k} units here and the rest on the continuation page. */
        private boolean breakAt(int k, Page page, List<PageFit> added) throws IOException {
            to = from + k;
            if (next != null && next.split != null && next.split.from == to) {
                return true;
            }
            removeContinuations(owner);
            PageFit root = owner;
            int depth = 2;
            while (root.parent != null) {
                root = root.parent;
                depth++;
            }
            String id = root.basePage().id() + "--" + depth;
            Page base = layouter.continuation(owner.basePage(), continueLayout, into, id);
            PageFit c = new PageFit(owner, base);
            c.splitChecked = true;
            Layout.Box intoBox = base.layout().boxes().stream().filter(b -> b.id().equals(into)).findFirst().orElseThrow();
            Layout.Split own = intoBox.rules().split();
            c.split = own == null
                    ? new SplitState(c, into, rule, continueLayout, into, source, units, to)
                    : new SplitState(c, into, own, own.continueIn(base.layout()), own.intoBox(intoBox), source, units, to);
            next = c;
            added.add(c);
            return true;
        }

        /** For the report: where the box broke, and where it continues. */
        String describe() {
            return "box " + boxId + ": units " + (from + 1) + "-" + to + " of " + units.size() + " here, the rest on page "
                    + (next == null ? "?" : next.basePage().id()) + " (the break is before line " + units.get(to).line() + ")";
        }
    }

    /** The split state of a page as laid out: its layout's splitting box, if it has two units or more. */
    private SplitState splitStateOf(PageFit pf, Page page) {
        Layout.Box box = page.layout().splitting();
        if (pf.parent != null || box == null) {
            return null;
        }
        Page.PlacedBox placed = page.boxes().stream().filter(b -> b.box() == box).findFirst().orElse(null);
        if (placed == null) {
            return null;
        }
        Layout.Split rule = box.rules().split();
        List<Splitting.Unit> units = Splitting.units(placed, rule);
        if (units.size() < 2) {
            return null;
        }
        return new SplitState(pf, box.id(), rule, rule.continueIn(page.layout()), rule.intoBox(box), placed, units, 0);
    }

    // --- one box's remedies ----------------------------------------------------------------

    private static final class BoxFit {
        final String path;
        final Page.PlacedBox placed;
        final List<Remedy> fit;
        final List<String> tried = new ArrayList<>();
        int remedy = -1;
        Search shrink;
        Remedy.Tighten tighten;
        Variants.Variant variant;
        boolean shortestAtOnce;
        List<Node> optional = List.of();
        int omitted;
        Integer fixedLines;
        Search truncate;
        Search search; // the one in progress, if any

        final int stopAt;

        BoxFit(String path, Page.PlacedBox placed, int stopAt) {
            this.path = path;
            this.placed = placed;
            this.fit = placed.box().rules().fit();
            this.stopAt = stopAt;
        }

        double scale() {
            return shrink == null ? 1 : shrink.candidate;
        }

        Integer lines() {
            return truncate != null ? Integer.valueOf((int) truncate.candidate) : fixedLines;
        }

        /** Boxes of HTML (and of inner layouts) zoom their contents; boxes of plain text set a font size. */
        boolean zoomsChildren() {
            Layout.Box b = placed.box();
            return !(b.isText() || b.isFileInfo() || (b.kind() == Layout.Kind.SELECT && b.as() != Layout.As.HTML));
        }

        /** The next step: more of the current remedy, else the next remedy that can do something. */
        boolean advance() {
            while (true) {
                if (remedy >= 0 && remedy < stopAt && stepAgain()) {
                    return true;
                }
                remedy++;
                if (remedy >= stopAt) {
                    return false;
                }
                if (start(fit.get(remedy))) {
                    tried.add(fit.get(remedy).describe());
                    return true;
                }
            }
        }

        private boolean start(Remedy r) {
            switch (r) {
                case Remedy.Shrink s -> {
                    shrink = new Search(s.min(), 1, false);
                    search = shrink;
                    return true;
                }
                case Remedy.Tighten t -> {
                    tighten = t;
                    return true;
                }
                case Remedy.Variant v -> {
                    if (!hasVariants()) {
                        return false;
                    }
                    shortestAtOnce = v.shortest();
                    return shorter();
                }
                case Remedy.DropOptional d -> {
                    optional = optionalBlocks();
                    if (optional.isEmpty()) {
                        return false;
                    }
                    omitted = 1;
                    return true;
                }
                case Remedy.Truncate t -> {
                    if (t.lines() != null) {
                        fixedLines = t.lines();
                    } else {
                        truncate = new Search(1, 1000, true);
                        search = truncate;
                    }
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        /** More of the current remedy: a shorter variant, another optional block. */
        private boolean stepAgain() {
            Remedy r = fit.get(remedy);
            if (r instanceof Remedy.Variant) {
                return shorter();
            }
            if (r instanceof Remedy.DropOptional && omitted < optional.size()) {
                omitted++;
                return true;
            }
            return false;
        }

        /** A shorter variant that changes what the box shows (a group without it shows what it did). */
        private boolean shorter() {
            Variants.Variant now = variant != null ? variant : placed.prefer();
            List<Node> shown = chosen(now);
            Variants.Variant[] all = Variants.Variant.values();
            int from = now == null ? 0 : now.ordinal();
            for (int i = shortestAtOnce ? all.length - 1 : from + 1; i < all.length; i++) {
                if (!chosen(all[i]).equals(shown)) {
                    variant = all[i];
                    return true;
                }
            }
            return false;
        }

        /** The variant each of the box's groups shows for {@code prefer}. */
        private List<Node> chosen(Variants.Variant prefer) {
            List<Node> out = new ArrayList<>();
            for (Node n : placed.nodes()) {
                collectChosen(n, prefer, out);
            }
            return out;
        }

        private static void collectChosen(Node n, Variants.Variant prefer, List<Node> into) {
            if (Variants.isGroup(n)) {
                into.add(Variants.choose(n, prefer));
            }
            for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
                collectChosen(c, prefer, into);
            }
        }

        private boolean hasVariants() {
            for (Node n : placed.nodes()) {
                if (any(n, Variants::isGroup)) {
                    return true;
                }
            }
            return false;
        }

        /** The box's {@code {! optional}} blocks, least important first: no priority, then the highest number, then the last. */
        private List<Node> optionalBlocks() {
            List<Node> found = new ArrayList<>();
            for (Node n : placed.nodes()) {
                collectOptional(n, found);
            }
            List<Node> ordered = new ArrayList<>(found);
            Collections.reverse(ordered);
            ordered.sort(Comparator.comparingInt(BoxFit::importance));
            return List.copyOf(ordered);
        }

        /** Lower goes first: no priority is least important, then priority N before N-1. */
        private static int importance(Node n) {
            String p = attributes(n).values().get("priority");
            return p == null ? Integer.MIN_VALUE : -Integer.parseInt(p);
        }

        private static void collectOptional(Node n, List<Node> into) {
            dev.noregressions.paperband.Attributes a = attributes(n);
            if (a != null && a.tags().contains("optional")) {
                into.add(n);
                return; // what's inside goes with it
            }
            for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
                collectOptional(c, into);
            }
        }

        private static dev.noregressions.paperband.Attributes attributes(Node n) {
            return MarkdownFile.attributesOf(n);
        }

        private static boolean any(Node n, java.util.function.Predicate<Node> test) {
            if (test.test(n)) {
                return true;
            }
            for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
                if (any(c, test)) {
                    return true;
                }
            }
            return false;
        }

        /** What this box ended up with, for the report. */
        List<String> done() {
            List<String> out = new ArrayList<>();
            if (scale() < 1) {
                out.add("text at " + Remedy.percent(scale()));
            }
            if (tighten != null) {
                out.add("tightened");
            }
            if (variant != null) {
                out.add("variant " + variant.id());
            }
            if (omitted > 0) {
                out.add("left out " + omitted + " optional block(s)");
            }
            if (lines() != null) {
                out.add("cut after " + lines() + " line(s)");
            }
            return out;
        }
    }

    /**
     * The largest value that fits, between a lowest value to try first and one known not to fit:
     * the lowest is measured first; if even that doesn't fit, it's kept and the search ends;
     * otherwise halving continues until the two are within a step.
     */
    static final class Search {
        final boolean whole;
        double lo;
        double hi;
        double candidate;
        boolean probing = true;
        boolean done;
        boolean fits;

        Search(double first, double failing, boolean whole) {
            this.candidate = first;
            this.hi = failing;
            this.whole = whole;
        }

        void record(boolean ok) {
            if (probing) {
                probing = false;
                if (!ok) {
                    done = true;
                    return;
                }
                lo = candidate;
            } else if (ok) {
                lo = candidate;
            } else {
                hi = candidate;
            }
            if (hi - lo <= (whole ? 1 : 0.01)) {
                done = true;
                fits = true;
                candidate = lo;
                return;
            }
            candidate = whole ? Math.floor((lo + hi) / 2) : (lo + hi) / 2;
        }
    }

    // --- boxes by path ---------------------------------------------------------------------

    /** The placed box at {@code path}, e.g. {@code steps[1]/command}, or null. */
    static Page.PlacedBox find(List<Page.PlacedBox> boxes, String path) {
        String[] parts = path.split("/", 2);
        Segment seg = Segment.of(parts[0]);
        for (Page.PlacedBox b : boxes) {
            if (!b.box().id().equals(seg.id)) {
                continue;
            }
            if (parts.length == 1) {
                return seg.index == null ? b : null;
            }
            int i = seg.index == null ? 0 : seg.index;
            return i < b.nested().size() ? find(b.nested().get(i).boxes(), parts[1]) : null;
        }
        return null;
    }

    /** {@code boxes} with the box at {@code path} replaced by {@code change}'s result. */
    static List<Page.PlacedBox> update(List<Page.PlacedBox> boxes, String path,
                                       java.util.function.UnaryOperator<Page.PlacedBox> change) {
        String[] parts = path.split("/", 2);
        Segment seg = Segment.of(parts[0]);
        List<Page.PlacedBox> out = new ArrayList<>();
        for (Page.PlacedBox b : boxes) {
            if (!b.box().id().equals(seg.id)) {
                out.add(b);
            } else if (parts.length == 1) {
                out.add(change.apply(b));
            } else {
                int i = seg.index == null ? 0 : seg.index;
                List<Page.Nested> nested = new ArrayList<>(b.nested());
                if (i < nested.size()) {
                    Page.Nested n = nested.get(i);
                    nested.set(i, new Page.Nested(n.layout(), update(n.boxes(), parts[1], change), n.match()));
                }
                out.add(b.withNested(List.copyOf(nested)));
            }
        }
        return out;
    }

    private record Segment(String id, Integer index) {
        static Segment of(String s) {
            int open = s.indexOf('[');
            return open < 0 ? new Segment(s, null)
                    : new Segment(s.substring(0, open), Integer.parseInt(s.substring(open + 1, s.length() - 1)));
        }
    }

    private static String cssString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "");
    }
}
