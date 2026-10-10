package dev.noregressions.paperband.layout;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Page geometry in millimetres. Web output flows and ignores it; PDF and PPTX will use it.
 *
 * <pre>
 * page:
 *   size: A4                 # A3 A4 A5 letter legal "16:9" "4:3", or { width: 254mm, height: 143mm }
 *   orientation: landscape   # portrait (default) or landscape; swaps width and height
 *   margin: 15mm
 * </pre>
 *
 * Lengths take {@code mm}, {@code cm}, {@code in}, {@code pt} or {@code px} (96 per inch).
 * Ratios must be quoted: YAML reads an unquoted {@code 16:9} as the number 969.
 */
public record PageSpec(double width, double height, double margin) {

    public static final PageSpec A4 = new PageSpec(210, 297, 0);

    private static final Map<String, double[]> SIZES = Map.of(
            "a3", new double[] {297, 420},
            "a4", new double[] {210, 297},
            "a5", new double[] {148, 210},
            "letter", new double[] {215.9, 279.4},
            "legal", new double[] {215.9, 355.6},
            // PowerPoint's default slide sizes: 13.333 x 7.5 in and 10 x 7.5 in, already landscape.
            "16:9", new double[] {338.667, 190.5},
            "4:3", new double[] {254, 190.5});

    private static final Pattern LENGTH = Pattern.compile("\\s*(-?\\d+(?:\\.\\d+)?)\\s*(mm|cm|in|pt|px)\\s*");

    /** Reads a {@code page:} mapping; anything it doesn't set comes from {@code base}. */
    static PageSpec parse(YamlSpec spec, PageSpec base) throws ConfigException {
        double w = base.width();
        double h = base.height();
        Object size = spec.raw("size");
        if (size instanceof Map) {
            YamlSpec s = YamlSpec.of(size, spec.child("size"));
            w = length(s.requiredString("width"), s.child("width"));
            h = length(s.requiredString("height"), s.child("height"));
            s.finish();
        } else if (size instanceof Number n) {
            throw spec.error("size", "got the number " + n + "; YAML reads an unquoted ratio like 16:9 as a number,"
                    + " so write it in quotes: size: \"16:9\"");
        } else if (size != null) {
            double[] named = SIZES.get(size.toString().toLowerCase(Locale.ROOT));
            if (named == null) {
                throw spec.error("size", "unknown size \"" + size + "\"; use one of "
                        + SIZES.keySet().stream().sorted().toList() + " or { width: ..., height: ... }");
            }
            w = named[0];
            h = named[1];
        }
        String orientation = spec.string("orientation", null);
        if (orientation != null) {
            boolean landscape = switch (orientation) {
                case "portrait" -> false;
                case "landscape" -> true;
                default -> throw spec.error("orientation", "expected portrait or landscape, got \"" + orientation + "\"");
            };
            if (landscape != w > h && w != h) {
                double t = w;
                w = h;
                h = t;
            }
        }
        double margin = spec.has("margin") ? length(spec.string("margin", null), spec.child("margin")) : base.margin();
        spec.finish();
        if (w <= 0 || h <= 0) {
            throw new ConfigException(spec.context() + ": page width and height must be positive");
        }
        if (margin < 0 || 2 * margin >= Math.min(w, h)) {
            throw new ConfigException(spec.context() + ": margin " + margin + "mm leaves no room on a "
                    + w + " x " + h + "mm page");
        }
        return new PageSpec(w, h, margin);
    }

    /** Parses a length such as {@code 15mm} or {@code 1in} into millimetres. */
    static double length(String text, String context) throws ConfigException {
        Matcher m = LENGTH.matcher(text);
        if (!m.matches()) {
            throw new ConfigException(context + ": \"" + text + "\" isn't a length; use a number with mm, cm, in, pt or px");
        }
        double v = Double.parseDouble(m.group(1));
        return switch (m.group(2)) {
            case "mm" -> v;
            case "cm" -> v * 10;
            case "in" -> v * 25.4;
            case "pt" -> v * 25.4 / 72;
            default -> v * 25.4 / 96;
        };
    }
}
