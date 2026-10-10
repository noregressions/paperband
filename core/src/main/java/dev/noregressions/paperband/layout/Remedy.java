package dev.noregressions.paperband.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Something that can be done when a box or a page doesn't fit, listed under {@code fit:} and
 * tried in the order written. What one remedy did stays done while the next is tried.
 *
 * <pre>
 * boxes:
 *   - id: body
 *     select: rest
 *     fit:
 *       - shrink: { min: 85% }              # text as large as fits, never below min (default 85%)
 *       - tighten: { line-height: 1.2, gap: 1mm }   # or tighten: true for these defaults
 *       - variant: next                     # one variant shorter each time (next), or shortest at once
 *       - drop: optional                    # leave out {! optional} blocks, least important first
 *       - truncate: { lines: 12 }           # cut after 12 lines; truncate: true cuts to what fits
 *       - split                             # for a box with a split: rule, where splitting comes
 * fit:                                      # the page's own, once its boxes have nothing left to try
 *   - drop: boxes                           # boxes with drop.below-min, optional before normal
 *   - grid: compact                         # another of the layout's grids:
 *   - layout: tutorial-compact              # lay the page out again with another layout
 * </pre>
 *
 * A box with a {@code split:} rule tries the remedies listed before {@code split} first (all of
 * them, when {@code split} isn't listed). If they don't make it fit, they're undone and the box
 * splits at its normal size; then each page's part can use any of its remedies if it still
 * doesn't fit. {@code fit: [split, shrink]} splits straight away.
 */
public sealed interface Remedy {

    /** What it is, for reports: {@code shrink (min 85%)}. */
    String describe();

    /** Scales the box's text down, as far as {@code min}, to the largest size that fits. */
    record Shrink(double min) implements Remedy {
        public String describe() {
            return "shrink (min " + percent(min) + ")";
        }
    }

    /** Sets line height and the gap between blocks. */
    record Tighten(double lineHeight, String gap) implements Remedy {
        public String describe() {
            return "tighten (line-height " + num(lineHeight) + ", gap " + gap + ")";
        }
    }

    /** Shows shorter variants: one step at a time, or the shortest straight away. */
    record Variant(boolean shortest) implements Remedy {
        public String describe() {
            return shortest ? "variant: shortest" : "variant: next";
        }
    }

    /** Leaves out {@code {! optional}} blocks one at a time, least important first. */
    record DropOptional() implements Remedy {
        public String describe() {
            return "drop: optional";
        }
    }

    /** Cuts the box's text after {@code lines} lines, or (null) after as many as fit. */
    record Truncate(Integer lines) implements Remedy {
        public String describe() {
            return lines == null ? "truncate" : "truncate (" + lines + " lines)";
        }
    }

    /** Where splitting comes among a box's remedies (see the class comment). */
    record Split() implements Remedy {
        public String describe() {
            return "split";
        }
    }

    /** Page: leaves out boxes that allow it ({@code drop.below-min}), optional before normal. */
    record DropBoxes() implements Remedy {
        public String describe() {
            return "drop: boxes";
        }
    }

    /** Page: switches to another of the layout's {@code grids}. */
    record UseGrid(String name) implements Remedy {
        public String describe() {
            return "grid: " + name;
        }
    }

    /** Page: lays the page out again with another layout. */
    record UseLayout(String name) implements Remedy {
        public String describe() {
            return "layout: " + name;
        }
    }

    double DEFAULT_SHRINK_MIN = 0.85;
    double DEFAULT_LINE_HEIGHT = 1.2;
    String DEFAULT_GAP = "1mm";

    /** A box's {@code fit:} list. */
    static List<Remedy> box(YamlSpec owner) throws ConfigException {
        return list(owner, true);
    }

    /** A layout's {@code fit:} list. */
    static List<Remedy> page(YamlSpec owner) throws ConfigException {
        return list(owner, false);
    }

    private static List<Remedy> list(YamlSpec owner, boolean box) throws ConfigException {
        Object raw = owner.raw("fit");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> items)) {
            throw owner.error("fit", "expected a list of remedies, e.g. [{ shrink: { min: 90% } }, { variant: next }]");
        }
        List<Remedy> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            String at = owner.child("fit") + "[" + i + "]";
            Object item = items.get(i);
            if (box && "split".equals(item)) {
                out.add(new Split());
                continue;
            }
            if (!(item instanceof Map<?, ?> m) || m.size() != 1) {
                throw new ConfigException(at + ": expected one remedy, written as name: settings, got "
                        + YamlSpec.describe(item));
            }
            var e = m.entrySet().iterator().next();
            String name = String.valueOf(e.getKey());
            Remedy r = box ? boxRemedy(name, e.getValue(), at) : pageRemedy(name, e.getValue(), at);
            if (r != null) {
                out.add(r);
            }
        }
        return List.copyOf(out);
    }

    private static Remedy boxRemedy(String name, Object v, String at) throws ConfigException {
        return switch (name) {
            case "shrink" -> {
                YamlSpec s = settings(v, at + ".shrink");
                double min = s == null ? DEFAULT_SHRINK_MIN : percentOf(s.string("min", "85%"), s.child("min"));
                if (s != null) {
                    s.finish();
                }
                if (min <= 0 || min >= 1) {
                    throw new ConfigException(at + ".shrink.min: expected a percentage below 100%, e.g. 85%");
                }
                yield new Shrink(min);
            }
            case "tighten" -> {
                YamlSpec s = settings(v, at + ".tighten");
                double lineHeight = DEFAULT_LINE_HEIGHT;
                String gap = DEFAULT_GAP;
                if (s != null) {
                    String lh = s.string("line-height", null);
                    if (lh != null) {
                        try {
                            lineHeight = Double.parseDouble(lh);
                        } catch (NumberFormatException e) {
                            lineHeight = -1;
                        }
                        if (lineHeight <= 0 || lineHeight > 3) {
                            throw new ConfigException(s.child("line-height") + ": expected a number such as 1.2, got \""
                                    + lh + "\"");
                        }
                    }
                    gap = s.string("gap", DEFAULT_GAP);
                    PageSpec.length(gap, s.child("gap"));
                    s.finish();
                }
                yield new Tighten(lineHeight, gap);
            }
            case "variant" -> switch (String.valueOf(v)) {
                case "next" -> new Variant(false);
                case "shortest" -> new Variant(true);
                default -> throw new ConfigException(at + ".variant: \"" + v + "\" isn't one of [next, shortest]");
            };
            case "drop" -> {
                if (!"optional".equals(String.valueOf(v))) {
                    throw new ConfigException(at + ".drop: a box can drop: optional (its {! optional} blocks); "
                            + "dropping boxes is the layout's drop: boxes");
                }
                yield new DropOptional();
            }
            case "truncate" -> {
                YamlSpec s = settings(v, at + ".truncate");
                Integer lines = null;
                if (s != null) {
                    Object l = s.raw("lines");
                    if (!(l instanceof Integer n) || n < 1) {
                        throw new ConfigException(s.child("lines") + ": expected a whole number from 1, got "
                                + YamlSpec.describe(l));
                    }
                    lines = n;
                    s.finish();
                }
                yield new Truncate(lines);
            }
            case "otherwise" -> otherwise(v, at);
            case "split" -> {
                if (!Boolean.TRUE.equals(v)) {
                    throw new ConfigException(at + ".split: write split (or split: true); the box's split: rule says how");
                }
                yield new Split();
            }
            case "grid", "layout" -> throw new ConfigException(at + ": " + name + " is a page remedy: list it under"
                    + " the layout's own fit:");
            default -> throw new ConfigException(at + ": unknown remedy \"" + name
                    + "\"; a box can use shrink, tighten, variant, drop: optional, truncate");
        };
    }

    private static Remedy pageRemedy(String name, Object v, String at) throws ConfigException {
        return switch (name) {
            case "drop" -> {
                if (!"boxes".equals(String.valueOf(v))) {
                    throw new ConfigException(at + ".drop: a layout can drop: boxes (those with drop.below-min);"
                            + " drop: optional goes on a box");
                }
                yield new DropBoxes();
            }
            case "grid" -> new UseGrid(name(v, at + ".grid"));
            case "layout" -> new UseLayout(name(v, at + ".layout"));
            case "otherwise" -> otherwise(v, at);
            case "shrink", "tighten", "variant", "truncate" -> throw new ConfigException(at + ": " + name
                    + " is a box remedy: list it under the box's fit:");
            default -> throw new ConfigException(at + ": unknown remedy \"" + name
                    + "\"; a layout can use drop: boxes, grid, layout");
        };
    }

    /** {@code otherwise: fail}, the default, written out: nothing to add. */
    private static Remedy otherwise(Object v, String at) throws ConfigException {
        if (!"fail".equals(String.valueOf(v))) {
            throw new ConfigException(at + ".otherwise: the only choice is fail (the build fails when nothing fits)");
        }
        return null;
    }

    private static String name(Object v, String at) throws ConfigException {
        if (!(v instanceof String s) || !s.matches("[A-Za-z][A-Za-z0-9_-]*")) {
            throw new ConfigException(at + ": expected a name, got " + YamlSpec.describe(v));
        }
        return s;
    }

    /** {@code true} for the defaults, or a map of settings. */
    private static YamlSpec settings(Object v, String at) throws ConfigException {
        if (Boolean.TRUE.equals(v)) {
            return null;
        }
        if (v instanceof Map<?, ?>) {
            return YamlSpec.of(v, at);
        }
        throw new ConfigException(at + ": expected true (the defaults) or settings, got " + YamlSpec.describe(v));
    }

    Pattern PERCENT = Pattern.compile("(\\d+(?:\\.\\d+)?)%");

    private static double percentOf(String value, String at) throws ConfigException {
        Matcher m = PERCENT.matcher(value.strip());
        if (!m.matches()) {
            throw new ConfigException(at + ": expected a percentage such as 85%, got \"" + value + "\"");
        }
        return Double.parseDouble(m.group(1)) / 100;
    }

    static String percent(double v) {
        return num(v * 100) + "%";
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
