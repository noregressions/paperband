package dev.noregressions.paperband.fit;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Something on a measured page that doesn't fit, or didn't load.
 *
 * @param page   the page's id
 * @param source the page's source file, or null for a generated page
 * @param box    the box's path, or null for the page itself (or a font)
 * @param amount how much, in millimetres, for the size problems; else 0
 * @param detail what the problem is about: an image's src, a font's name; else null
 * @param tried  the remedies tried on its page, in order, e.g. {@code box body: shrink (min 85%)}
 */
public record Problem(String page, Path source, String box, Kind kind, double amount, String detail,
                      List<String> tried) {

    public Problem(String page, Path source, String box, Kind kind, double amount, String detail) {
        this(page, source, box, kind, amount, detail, List.of());
    }

    public Problem withTried(List<String> tried) {
        return new Problem(page, source, box, kind, amount, detail, List.copyOf(tried));
    }

    /** True for what remedies can work on: sizes and places, not images or fonts that didn't load. */
    public boolean fixable() {
        return kind != Kind.IMAGE && kind != Kind.FONT;
    }

    public enum Kind {
        /** The box's content is taller than the box. */
        TALLER,
        /** The box's content is wider than the box (long code lines, wide tables). */
        WIDER,
        /** The box reaches past the edge of the page. */
        OFF_PAGE,
        /** The box reaches into the page margin. */
        IN_MARGIN,
        /** The page's content is taller than the page. */
        PAGE_TALLER,
        /** The page's content is wider than the page. */
        PAGE_WIDER,
        /** An image didn't load. */
        IMAGE,
        /** A font didn't load. */
        FONT
    }

    /** E.g. {@code page docs-a-md (docs/a.md), box body: content is 12.3mm taller than the box}. */
    public String message() {
        String where = kind == Kind.FONT ? "" : "page " + page + (source == null ? "" : " (" + source.toString().replace('\\', '/') + ")")
                + (box == null ? "" : ", box " + box) + ": ";
        String mm = format(amount) + "mm";
        return where + switch (kind) {
            case TALLER -> "content is " + mm + " taller than the box";
            case WIDER -> "content is " + mm + " wider than the box";
            case OFF_PAGE -> "the box reaches " + mm + " past the edge of the page";
            case IN_MARGIN -> "the box reaches " + mm + " into the page margin";
            case PAGE_TALLER -> "content is " + mm + " taller than the page";
            case PAGE_WIDER -> "content is " + mm + " wider than the page";
            case IMAGE -> "image \"" + detail + "\" didn't load";
            case FONT -> "font " + detail + " didn't load";
        };
    }

    static String format(double mm) {
        return String.format(Locale.ROOT, "%.1f", mm);
    }
}
