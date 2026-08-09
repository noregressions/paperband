package dev.noregressions.paperband.render;

import java.util.Map;

/**
 * Resolves a {@code page:} yaml block (declared under {@code vars.page},
 * cascading exactly like any other {@code vars} entry — book root sets the
 * default, a folder-level {@code paperband.yaml} can override it wholesale
 * for one edition) into a real {@link PageSpec} plus a font-scale multiplier.
 *
 * <pre>
 * vars:
 *   page:
 *     size: A5                              # preset name, or {width, height[, unit]}
 *     margins: { top: 20, right: 18, bottom: 15, left: 18 }   # unit defaults to mm
 *     orientation: landscape                # portrait (default) | landscape
 *     fontScale: 1.09                        # optional; auto-derived from page width if omitted
 * </pre>
 *
 * <p>Every field is optional and independently overridable; an unset field
 * falls back to whatever {@code base} (typically the CLI {@code --page-size}
 * flag's resolved preset) already supplies. {@code size} and {@code margins}
 * are each replaced wholesale when present — same shallow-override semantics
 * {@code vars:} already uses elsewhere in the cascade, not a deep per-edge
 * merge — so a {@code margins:} override should restate every edge it wants,
 * not just the one it's changing relative to a parent yaml.
 *
 * <p>{@code fontScale} is not part of {@link PageSpec} (a renderer-geometry
 * model); it's a separate multiplier consumed by {@code LayoutEngine} to
 * drive the CSS type-scale dial ({@code html { font-size: calc(11pt *
 * var(--pw-font-scale, 1)) } }). It's deliberately nullable and conservative:
 * bundled themes already carry curated, hand-tuned font-size rules per named
 * preset (e.g. {@code html.size-6x9 { font-size: 12pt }} — not a naive ratio,
 * a legibility call for that trim size), and this resolver must not silently
 * override those with a generic formula. So {@code fontScale} resolves
 * non-null only when there's a real reason to touch the dial: an explicit
 * {@code page.fontScale} in yaml (always wins), or the resolved page size
 * isn't one of the four named presets (a4/a5/letter/6x9) at all — a genuinely
 * custom size has no curated theme rule to defer to, so an automatic default
 * (derived from page width relative to A4's) is better than nothing. For a
 * named preset with no explicit override, this returns {@code null} and
 * {@code LayoutEngine} leaves the CSS var unset, so the theme's own rule
 * applies exactly as it always has.
 */
public final class PageConfigResolver {

    private PageConfigResolver() {}

    /** A4's width in mm — the reference point for the auto font-scale default. */
    private static final double REFERENCE_WIDTH_MM = 210.0;

    /** The named presets bundled themes already carry curated font-size rules for. */
    private static final java.util.Set<PageSize> KNOWN_PRESETS = java.util.Set.of(
            PageSize.A4, PageSize.A5, PageSize.LETTER, PageSize.LEGAL,
            PageSize.of(6, 9, Unit.INCH));

    public record Resolved(PageSpec pageSpec, Double fontScale) {}

    /**
     * @param raw  the parsed {@code vars.page} map, or null/empty if not declared
     * @param base the PageSpec to fall back to for any field {@code raw} doesn't set
     *             (normally whatever the CLI {@code --page-size} preset resolved to)
     */
    public static Resolved resolve(Map<String, Object> raw, PageSpec base) {
        if (raw == null || raw.isEmpty()) {
            return new Resolved(base, KNOWN_PRESETS.contains(base.size()) ? null : autoFontScale(base.size()));
        }
        PageSize size = resolveSize(raw.get("size"), base.size());
        Margins margins = resolveMargins(raw.get("margins"), base.margins());
        Orientation orientation = resolveOrientation(raw.get("orientation"), base.orientation());
        PageSpec spec = new PageSpec(size, margins, orientation);

        Object fontScaleNode = raw.get("fontScale");
        Double fontScale;
        if (fontScaleNode != null) {
            fontScale = toDouble(fontScaleNode);
        } else if (!KNOWN_PRESETS.contains(size)) {
            fontScale = autoFontScale(size);
        } else {
            fontScale = null;
        }
        return new Resolved(spec, fontScale);
    }

    /** Width-ratio default: a page half as wide as A4 gets roughly half the A4 baseline size. */
    private static double autoFontScale(PageSize size) {
        double widthMm = size.unit().toMillimetres(size.width());
        return widthMm / REFERENCE_WIDTH_MM;
    }

    private static PageSize resolveSize(Object node, PageSize fallback) {
        if (node == null) return fallback;
        if (node instanceof String s) {
            return switch (s.trim().toLowerCase()) {
                case "a4"    -> PageSize.A4;
                case "a5"    -> PageSize.A5;
                case "letter" -> PageSize.LETTER;
                case "legal" -> PageSize.LEGAL;
                case "6x9"   -> PageSize.of(6, 9, Unit.INCH);
                default -> throw new IllegalArgumentException(
                        "page.size: unknown preset '" + s + "' (expected a4, a5, letter, legal, 6x9, "
                        + "or a {width, height[, unit]} map)");
            };
        }
        if (node instanceof Map<?, ?> m) {
            Object w = m.get("width");
            Object h = m.get("height");
            if (w == null || h == null) {
                throw new IllegalArgumentException(
                        "page.size: custom size needs both 'width' and 'height'");
            }
            Unit unit = unitOf(m.get("unit"), Unit.MM);
            return PageSize.of(toDouble(w), toDouble(h), unit);
        }
        throw new IllegalArgumentException(
                "page.size: expected a preset name or {width, height} map, got: " + node);
    }

    private static Margins resolveMargins(Object node, Margins fallback) {
        if (node == null) return fallback;
        if (!(node instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException(
                    "page.margins: expected a {top, right, bottom, left} map, got: " + node);
        }
        Unit unit = unitOf(m.get("unit"), fallback.unit());
        double top    = edgeOr(m.get("top"),    fallback.top(),    fallback.unit(), unit);
        double right  = edgeOr(m.get("right"),  fallback.right(),  fallback.unit(), unit);
        double bottom = edgeOr(m.get("bottom"), fallback.bottom(), fallback.unit(), unit);
        double left   = edgeOr(m.get("left"),   fallback.left(),   fallback.unit(), unit);
        return new Margins(top, right, bottom, left, unit);
    }

    private static double edgeOr(Object node, double fallbackValue, Unit fallbackUnit, Unit targetUnit) {
        if (node != null) return toDouble(node);
        return convert(fallbackValue, fallbackUnit, targetUnit);
    }

    private static Orientation resolveOrientation(Object node, Orientation fallback) {
        if (node == null) return fallback;
        String s = node.toString().trim().toLowerCase();
        return switch (s) {
            case "portrait"  -> Orientation.PORTRAIT;
            case "landscape" -> Orientation.LANDSCAPE;
            default -> throw new IllegalArgumentException(
                    "page.orientation: expected 'portrait' or 'landscape', got: " + node);
        };
    }

    private static Unit unitOf(Object node, Unit fallback) {
        if (node == null) return fallback;
        String s = node.toString().trim().toLowerCase();
        return switch (s) {
            case "mm"    -> Unit.MM;
            case "in", "inch" -> Unit.INCH;
            case "pt", "point" -> Unit.POINT;
            default -> throw new IllegalArgumentException(
                    "page: unknown unit '" + s + "' (expected mm, in, or pt)");
        };
    }

    private static double convert(double value, Unit from, Unit to) {
        if (from == to) return value;
        double mm = from.toMillimetres(value);
        return mm / to.toMillimetres(1);
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("page: expected a number, got: " + o, e);
        }
    }
}
