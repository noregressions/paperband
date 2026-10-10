package dev.noregressions.paperband.fit;

import dev.noregressions.paperband.layout.Layout;
import dev.noregressions.paperband.layout.Page;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a measurement into the problems a build fails for. With nothing to fix them yet, every
 * one fails the build:
 * <ul>
 *   <li>a box whose content is taller or wider than the box;</li>
 *   <li>a box that reaches past the edge of its page, or (a page's own box, unless it's placed
 *       with {@code at}) into the page margin;</li>
 *   <li>on a page with a fixed shape (a grid, a stack or {@code at} boxes), content taller or
 *       wider than the page, when no box on it has a problem (a box that doesn't fit spills over
 *       the page too); a page without a fixed shape flows onto as many sheets as it needs;</li>
 *   <li>an image or a font that didn't load.</li>
 * </ul>
 * Differences under {@value #TOLERANCE}mm (rounding) don't count.
 */
public final class FitCheck {

    /** Less than this, in millimetres, is rounding, not a problem: about a pixel. */
    static final double TOLERANCE = 0.3;

    private FitCheck() {
    }

    public static List<Problem> check(List<Page> pages, Measurement m) {
        Map<String, Page> byId = new HashMap<>();
        pages.forEach(p -> byId.put(p.id(), p));
        List<Problem> out = new ArrayList<>();
        for (Measurement.Sheet sheet : m.pages()) {
            Page page = byId.get(sheet.id());
            if (page == null) {
                continue;
            }
            boolean fixed = page.layout().fixed();
            int before = out.size();
            Map<String, Layout.Box> topLevel = new HashMap<>();
            page.boxes().forEach(b -> topLevel.put(b.box().id(), b.box()));
            for (Measurement.Box box : sheet.boxes()) {
                if (box.overflowY() > TOLERANCE) {
                    out.add(problem(page, box, Problem.Kind.TALLER, box.overflowY()));
                }
                if (box.overflowX() > TOLERANCE) {
                    out.add(problem(page, box, Problem.Kind.WIDER, box.overflowX()));
                }
                double past = Math.max(Math.max(-box.x(), -box.y()), box.x() + box.w() - sheet.width());
                if (fixed) {
                    past = Math.max(past, box.y() + box.h() - sheet.height());
                }
                if (past > TOLERANCE) {
                    out.add(problem(page, box, Problem.Kind.OFF_PAGE, past));
                    continue;
                }
                Layout.Box layoutBox = topLevel.get(box.path());
                if (layoutBox != null && layoutBox.at() == null) {
                    double[] mg = sheet.margin();
                    double into = Math.max(Math.max(mg[3] - box.x(), mg[0] - box.y()),
                            box.x() + box.w() - (sheet.width() - mg[1]));
                    if (fixed) {
                        into = Math.max(into, box.y() + box.h() - (sheet.height() - mg[2]));
                    }
                    if (into > TOLERANCE) {
                        out.add(problem(page, box, Problem.Kind.IN_MARGIN, into));
                    }
                }
            }
            // A box that doesn't fit spills over the page too; the page is only reported when no box explains it.
            if (out.size() == before) {
                if (fixed && sheet.overflowY() > TOLERANCE) {
                    out.add(new Problem(page.id(), page.source(), null, Problem.Kind.PAGE_TALLER, sheet.overflowY(), null));
                }
                if (sheet.overflowX() > TOLERANCE) {
                    out.add(new Problem(page.id(), page.source(), null, Problem.Kind.PAGE_WIDER, sheet.overflowX(), null));
                }
            }
        }
        for (Measurement.BrokenImage image : m.brokenImages()) {
            Page page = byId.get(image.page());
            out.add(new Problem(image.page(), page == null ? null : page.source(),
                    image.box() == null || image.box().isEmpty() ? null : image.box(), Problem.Kind.IMAGE, 0, image.src()));
        }
        for (String font : m.failedFonts()) {
            out.add(new Problem(null, null, null, Problem.Kind.FONT, 0, font));
        }
        return List.copyOf(out);
    }

    private static Problem problem(Page page, Measurement.Box box, Problem.Kind kind, double amount) {
        return new Problem(page.id(), page.source(), box.path(), kind, amount, null);
    }
}
