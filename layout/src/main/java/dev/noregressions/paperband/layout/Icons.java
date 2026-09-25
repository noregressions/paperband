package dev.noregressions.paperband.layout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code :name:} icon references into inline SVG.
 *
 * <pre>
 * | :users: Reach | :trophy: Authority |
 * </pre>
 *
 * <p>Emoji were the obvious way to put a picture in a line of text, and they
 * don't survive the trip to a PDF: the headless Chromium that renders the book
 * drops a colour-emoji glyph whenever the surrounding text is bold, a CI image
 * usually has no emoji font at all, and where they do render they look
 * different on every platform and ignore the theme. An inline SVG drawn in
 * {@code currentColor} has none of those problems. It prints crisply, looks the
 * same on the site, and takes its colour and size from the text around it.
 *
 * <p><strong>Where it runs.</strong> On each finished page, after the templates
 * and after {@link CardLinks} — not in the markdown. Markdown never parses
 * inside raw HTML, so a reference in a hand-written {@code <td>} (or in the
 * markup a Pebble loop emits) would be invisible to a markdown extension; and a
 * card's headings, title and oneliner reach the page through templates that
 * escape them as text. The finished page is the one place every one of those
 * arrives as the same thing.
 *
 * <p><strong>What is never touched:</strong> the contents of {@code head},
 * {@code title}, {@code script}, {@code style}, {@code pre}, {@code code},
 * {@code textarea} and {@code svg}, attribute values, and comments. A literal
 * {@code :name:} in a code sample stays literal. Any element classed
 * {@code no-icons} is left alone the same way: the back-of-book index uses it,
 * since a term it extracted from a code span is a word, not a reference.
 *
 * <p><strong>The syntax</strong> is a lowercase name between colons,
 * {@code [a-z][a-z0-9]*(-[a-z0-9]+)*}, standing on its own: neither colon may
 * touch a letter, digit, underscore or another colon. That is what keeps
 * {@code Foo::bar:}, {@code group:artifact:goal} and {@code 10:30:45} out of
 * it. {@code ::name:} is the escape, and renders as a literal {@code :name:}.
 *
 * <p><strong>Resolution</strong> walks the book's own {@code icons/<name>.svg}
 * (in the book's home, set by {@link LayoutEngine#setIconsDir}) and then the bundled
 * <a href="https://lucide.dev">Lucide</a> set, so a book can add names or
 * replace a bundled drawing. An unknown name fails the build, with a
 * suggestion: a typo would otherwise ship as literal text.
 */
public final class Icons {

    /** Where the bundled set lives: one {@code name<TAB>inner-svg} line per icon. */
    private static final String BUNDLED = "icons/lucide.tsv";

    /** The attributes every bundled Lucide icon shares, minus its class. */
    private static final String BUNDLED_WRAPPER = "xmlns=\"http://www.w3.org/2000/svg\" "
            + "width=\"1em\" height=\"1em\" viewBox=\"0 0 24 24\" fill=\"none\" "
            + "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" "
            + "stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"";

    /** A valid icon name, as written between the colons and as a file's basename. */
    static final String NAME = "[a-z][a-z0-9]*(?:-[a-z0-9]+)*";

    /** A reference in running text. Group 1 is {@code :} or the {@code ::} escape. */
    private static final Pattern REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9_:])(::?)(" + NAME + "):(?![A-Za-z0-9_:])");

    private static final Pattern VALID_NAME = Pattern.compile(NAME);

    /** Elements whose text is never scanned — see the class javadoc. */
    private static final Set<String> OPAQUE = Set.of(
            "head", "title", "script", "style", "pre", "code", "textarea", "svg");

    /** A {@code class} attribute carrying the {@code no-icons} token. */
    private static final Pattern NO_ICONS = Pattern.compile(
            "\\sclass\\s*=\\s*([\"'])(?:[^\"']*\\s)?no-icons(?:\\s[^\"']*)?\\1",
            Pattern.CASE_INSENSITIVE);

    /** Elements with no content, which a no-icons class can't open a region on. */
    private static final Set<String> VOID = Set.of("area", "base", "br", "col", "embed", "hr",
            "img", "input", "link", "meta", "source", "track", "wbr");

    /** Opaque elements whose content is raw text, not markup: skipped to their close tag. */
    private static final Set<String> RAW_TEXT = Set.of("script", "style", "textarea", "title");

    /** Loaded once per JVM: the set is immutable and ~400 KB. */
    private static volatile Map<String, String> bundled;

    private final Path bookIconsDir;
    private final Map<String, String> bookCache = new HashMap<>();

    /**
     * @param bookIconsDir the book's {@code icons/} directory, or null for the
     *                     bundled set alone. A directory that doesn't exist is
     *                     the same as none.
     */
    public Icons(Path bookIconsDir) {
        this.bookIconsDir = bookIconsDir == null ? null : bookIconsDir.toAbsolutePath().normalize();
    }

    /**
     * Replace every {@code :name:} reference in {@code html}'s text with its SVG.
     *
     * @param html a finished page, or any fragment of one
     * @return the page with icons drawn in
     * @throws LayoutException naming every unknown icon, with suggestions
     */
    public String apply(String html) {
        if (html == null || html.indexOf(':') < 0) return html;
        StringBuilder out = new StringBuilder(html.length() + 256);
        Map<String, String> unknown = new LinkedHashMap<>();
        Map<String, Integer> opaque = new HashMap<>();
        int opaqueDepth = 0;
        String literalTag = null;
        int literalNest = 0;
        int i = 0;
        int n = html.length();
        while (i < n) {
            char c = html.charAt(i);
            if (c != '<') {
                int next = html.indexOf('<', i);
                if (next < 0) next = n;
                String text = html.substring(i, next);
                out.append(opaqueDepth > 0 ? text : replace(text, unknown));
                i = next;
                continue;
            }
            if (html.startsWith("<!--", i)) {
                int end = html.indexOf("-->", i + 4);
                end = end < 0 ? n : end + 3;
                out.append(html, i, end);
                i = end;
                continue;
            }
            int end = tagEnd(html, i);
            String tag = html.substring(i, end);
            out.append(tag);
            i = end;
            String name = tagName(tag);
            if (name == null) continue;
            // An element classed no-icons keeps its text literal, children
            // and all: counted by tag name, so a nested <span> inside a
            // no-icons <span> doesn't end it early.
            if (literalTag != null) {
                if (name.equals(literalTag) && !tag.endsWith("/>")) {
                    literalNest += tag.startsWith("</") ? -1 : 1;
                    if (literalNest == 0) {
                        literalTag = null;
                        opaqueDepth--;
                    }
                }
            } else if (!tag.startsWith("</") && !tag.endsWith("/>") && !VOID.contains(name)
                    && NO_ICONS.matcher(tag).find()) {
                literalTag = name;
                literalNest = 1;
                opaqueDepth++;
                continue;
            }
            if (!OPAQUE.contains(name)) continue;
            // Raw-text elements hold no markup, only text that may *mention*
            // markup — a stylesheet comment about <pre>, a script building an
            // <svg> string. Tokenizing it would open elements that never close
            // and leave the rest of the page unscanned, so jump to the close.
            if (RAW_TEXT.contains(name) && !tag.startsWith("</") && !tag.endsWith("/>")) {
                int close = indexOfIgnoreCase(html, "</" + name, i);
                if (close < 0) close = n;
                out.append(html, i, close);
                i = close;
                continue;
            }
            boolean closing = tag.startsWith("</");
            boolean selfClosing = tag.endsWith("/>");
            if (closing) {
                int d = opaque.getOrDefault(name, 0);
                if (d > 0) {
                    opaque.put(name, d - 1);
                    opaqueDepth--;
                }
            } else if (!selfClosing) {
                opaque.merge(name, 1, Integer::sum);
                opaqueDepth++;
            }
        }
        if (!unknown.isEmpty()) throw new LayoutException(unknownMessage(unknown));
        return out.toString();
    }

    /** Whether {@code name} resolves, in the book's icons or the bundled set. */
    public boolean has(String name) {
        return svg(name) != null;
    }

    /**
     * The SVG for {@code name}, ready to splice into a page, or null when no
     * such icon exists.
     */
    public String svg(String name) {
        String own = bookIcon(name);
        if (own != null) return own;
        String inner = bundled().get(name);
        return inner == null ? null
                : "<svg class=\"icon icon-" + name + "\" " + BUNDLED_WRAPPER + ">" + inner + "</svg>";
    }

    /** Every name {@link #svg} resolves: the book's own and the bundled set. */
    public Set<String> names() {
        Set<String> all = new TreeSet<>(bundled().keySet());
        if (bookIconsDir != null && Files.isDirectory(bookIconsDir)) {
            try (var files = Files.list(bookIconsDir)) {
                files.map(p -> p.getFileName().toString())
                        .filter(f -> f.endsWith(".svg"))
                        .map(f -> f.substring(0, f.length() - 4))
                        .filter(f -> VALID_NAME.matcher(f).matches())
                        .forEach(all::add);
            } catch (IOException e) {
                throw new LayoutException("Could not list icons in " + bookIconsDir + ": "
                        + e.getMessage());
            }
        }
        return all;
    }

    private String replace(String text, Map<String, String> unknown) {
        if (text.indexOf(':') < 0) return text;
        Matcher m = REFERENCE.matcher(text);
        StringBuilder sb = null;
        int last = 0;
        while (m.find()) {
            if (sb == null) sb = new StringBuilder(text.length() + 256);
            sb.append(text, last, m.start());
            String name = m.group(2);
            if (m.group(1).length() == 2) {
                sb.append(':').append(name).append(':');         // the escape
            } else {
                String svg = svg(name);
                if (svg == null) {
                    unknown.putIfAbsent(name, context(text, m.start(), m.end()));
                    sb.append(m.group());
                } else {
                    sb.append(svg);
                }
            }
            last = m.end();
        }
        if (sb == null) return text;
        sb.append(text, last, text.length());
        return sb.toString();
    }

    /** A book-supplied icon, prepared for inlining, or null when the book has none by that name. */
    private String bookIcon(String name) {
        if (bookIconsDir == null) return null;
        return bookCache.computeIfAbsent(name, k -> {
            Path file = bookIconsDir.resolve(k + ".svg");
            if (!Files.isRegularFile(file)) return null;
            try {
                return prepare(k, Files.readString(file, StandardCharsets.UTF_8), file);
            } catch (IOException e) {
                throw new LayoutException("Could not read icon " + file + ": " + e.getMessage());
            }
        });
    }

    /**
     * A book's SVG file as an inline icon: prolog and comments dropped, the
     * root given the {@code icon} classes and a 1em box. Scripts, event
     * handlers and {@code foreignObject} fail the build rather than being
     * cleaned — an icon has no business carrying any of them, and a silent
     * repair would hide whatever produced the file.
     */
    static String prepare(String name, String source, Path file) {
        String s = source.replaceAll("(?s)<\\?xml.*?\\?>", "")
                .replaceAll("(?s)<!DOCTYPE.*?>", "")
                .replaceAll("(?s)<!--.*?-->", "")
                .strip();
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("<svg")) {
            throw new LayoutException("Icon " + file + " is not an SVG: it must start with <svg>.");
        }
        if (lower.contains("<script") || lower.contains("<foreignobject")
                || Pattern.compile("\\son[a-z]+\\s*=").matcher(lower).find()) {
            throw new LayoutException("Icon " + file + " contains a script, an event handler or "
                    + "a foreignObject. Icons are inlined into every page that uses them, so "
                    + "they must be plain drawings.");
        }
        int rootEnd = tagEnd(s, 0);
        String root = s.substring(0, rootEnd);
        String rest = s.substring(rootEnd);
        String attrs = root.substring(4, root.endsWith("/>") ? root.length() - 2 : root.length() - 1)
                .replaceAll("\\s(?:width|height|class|aria-hidden|focusable)\\s*=\\s*(\"[^\"]*\"|'[^']*')", "");
        return "<svg class=\"icon icon-" + name + "\" width=\"1em\" height=\"1em\""
                + " aria-hidden=\"true\" focusable=\"false\"" + attrs
                + (root.endsWith("/>") ? "/>" : ">") + rest;
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from) {
        for (int j = from; j <= haystack.length() - needle.length(); j++) {
            if (haystack.regionMatches(true, j, needle, 0, needle.length())) return j;
        }
        return -1;
    }

    /** Index just past the {@code >} closing the tag that starts at {@code start}, quote-aware. */
    private static int tagEnd(String html, int start) {
        char quote = 0;
        for (int j = start + 1; j < html.length(); j++) {
            char c = html.charAt(j);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return j + 1;
            }
        }
        return html.length();
    }

    /** The lowercased element name of a tag, or null for a doctype, processing instruction or stray {@code <}. */
    private static String tagName(String tag) {
        int j = tag.startsWith("</") ? 2 : 1;
        int k = j;
        while (k < tag.length() && (Character.isLetterOrDigit(tag.charAt(k)) || tag.charAt(k) == '-')) k++;
        if (k == j || !Character.isLetter(tag.charAt(j))) return null;
        return tag.substring(j, k).toLowerCase(Locale.ROOT);
    }

    private static String context(String text, int start, int end) {
        String before = text.substring(Math.max(0, start - 30), start);
        String after = text.substring(end, Math.min(text.length(), end + 30));
        return ("…" + before + text.substring(start, end) + after + "…").replaceAll("\\s+", " ");
    }

    private String unknownMessage(Map<String, String> unknown) {
        Set<String> names = names();
        StringBuilder sb = new StringBuilder(unknown.size() == 1
                ? "Unknown icon:\n" : unknown.size() + " unknown icons:\n");
        for (Map.Entry<String, String> e : unknown.entrySet()) {
            sb.append("  :").append(e.getKey()).append(":  in \"").append(e.getValue()).append('"')
                    .append(suggest(e.getKey(), names)).append('\n');
        }
        sb.append("Icons come from ").append(bookIconsDir == null ? "" : bookIconsDir + " and ")
                .append("the bundled Lucide set (names as listed at https://lucide.dev/icons). "
                        + "Write ::name: for a literal :name:, or set vars: { icons: false } "
                        + "to turn icon references off for the book.");
        return sb.toString();
    }

    /** " — did you mean 'x'?" for the nearest names (ties listed), else names containing the one written. */
    private static String suggest(String written, Set<String> candidates) {
        List<String> containing = new ArrayList<>();
        List<String> nearest = new ArrayList<>();
        int bestDistance = Integer.MAX_VALUE;
        for (String c : candidates) {
            if (c.contains(written) && containing.size() < 4) containing.add(c);
            int d = distance(written, c);
            if (d < bestDistance) {
                bestDistance = d;
                nearest.clear();
            }
            if (d == bestDistance && nearest.size() < 3) nearest.add(c);
        }
        int tolerance = Math.max(1, written.length() / 3);
        if (!nearest.isEmpty() && bestDistance <= tolerance) {
            return " — did you mean '" + String.join("' or '", nearest) + "'?";
        }
        if (!containing.isEmpty()) return " — similar: " + String.join(", ", containing);
        return "";
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    private static Map<String, String> bundled() {
        Map<String, String> b = bundled;
        if (b != null) return b;
        synchronized (Icons.class) {
            if (bundled != null) return bundled;
            Map<String, String> m = new HashMap<>(4096);
            try (InputStream in = Icons.class.getClassLoader().getResourceAsStream(BUNDLED)) {
                if (in == null) throw new LayoutException("Bundled icon set missing: " + BUNDLED);
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab > 0) m.put(line.substring(0, tab), line.substring(tab + 1));
                }
            } catch (IOException e) {
                throw new LayoutException("Could not read the bundled icon set: " + e.getMessage());
            }
            bundled = Map.copyOf(m);
            return bundled;
        }
    }
}
