package dev.noregressions.paperband.layout;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * How a build's outputs look: the stylesheets Paperband itself adds to every document, and
 * optionally layouts. The built-in theme is {@link #DEFAULT}. A manifest's {@code theme:} names
 * another: a directory, relative to the manifest ({@code theme: themes/mine}), or one of the
 * themes Paperband brings, by name ({@code theme: forest}); a directory of that name wins, so a
 * project can keep its own copy ({@link #resolve}). Either way a theme is a directory that
 * changes the built-in one:
 *
 * <pre>
 * themes/mine/
 *   theme.css       # added after the parts below: set the --pb-* variables here, add rules
 *   web.css         # each of these replaces the built-in part of the same name;
 *   paged.css       #   a part the theme doesn't have stays the built-in one
 *   content.css
 *   site.css
 *   nav-side.css
 *   nav-top.css
 *   layouts/        # layouts every project using the theme gets; a project's own layout
 *                   #   of the same name replaces the theme's
 *   fonts/          # anything else the theme's stylesheets refer to: fonts, images
 * </pre>
 *
 * A theme that only changes colours and fonts needs just {@code theme.css}. The themes Paperband
 * brings are resources beside this class ({@code themes/<name>/}), in the same shape; the built-in
 * parts are {@code themes/default/}, the reference for what a part holds and which variables it reads.
 * <p>
 * In the document, the theme's styles come first, then the manifest's {@code stylesheets}, the
 * layouts' own styles, and what fit remedies set: each can override what's before it.
 * <p>
 * A {@code url(...)} in a theme's stylesheet that names a file in the theme's directory, relative
 * to it ({@code url("fonts/text.woff2")}), is replaced by the file itself, as a {@code data:} URL,
 * so the theme's fonts and images travel inside every document made with it. Any other URL is
 * kept as written.
 * <p>
 * Paged output measures the same everywhere only with fonts that are the same everywhere: the
 * bundled ones ({@link dev.noregressions.paperband.Fonts}), or ones the theme brings this way.
 */
public final class Theme {

    /** The stylesheets a theme is made of, each a file in its directory. */
    public enum Part {
        /** Web output: the flowing page, and the variables web output reads. */
        WEB("web.css"),
        /** Paged output (paged-html, pdf): fixed-size pages, and the variables paged output reads. */
        PAGED("paged.css"),
        /** Content in both: code, tables, images, the source view's highlighting. */
        CONTENT("content.css"),
        /** A web site's header and previous/next links ({@link WebSite}). */
        SITE("site.css"),
        /** A web site's side navigation. */
        NAV_SIDE("nav-side.css"),
        /** A web site's navigation bar. */
        NAV_TOP("nav-top.css");

        private final String file;

        Part(String file) {
            this.file = file;
        }

        public String file() {
            return file;
        }
    }

    /** The file of rules added after the parts. */
    public static final String EXTRA = "theme.css";

    /** The directory of a theme's layouts. */
    public static final String LAYOUTS = "layouts";

    private static final String BUILT_IN = "/dev/noregressions/paperband/themes/default/";

    /** What a theme brought by Paperband is called: also its directory among the resources. */
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

    /** The directory of the themes Paperband brings; found once (in a jar, its file system stays open). */
    private static Path bundled;

    /** The built-in theme. */
    public static final Theme DEFAULT = builtIn();

    private final Map<Part, String> parts;
    private final String extra;
    private final Path layouts;

    private Theme(Map<Part, String> parts, String extra, Path layouts) {
        this.parts = parts;
        this.extra = extra;
        this.layouts = layouts;
    }

    private static Theme builtIn() {
        Map<Part, String> parts = new EnumMap<>(Part.class);
        for (Part part : Part.values()) {
            try (InputStream in = Theme.class.getResourceAsStream(BUILT_IN + part.file())) {
                if (in == null) {
                    throw new IllegalStateException("built-in theme part missing: " + part.file());
                }
                parts.put(part, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new Theme(parts, "", null);
    }

    /**
     * The theme a manifest's {@code theme:} names: the directory {@code value}, relative to
     * {@code dir}, if there is one; else the theme of that name Paperband brings.
     *
     * @param shown how to refer to the manifest in messages
     */
    public static Theme resolve(String value, Path dir, String shown) throws IOException {
        Path own;
        try {
            own = dir.resolve(value);
        } catch (java.nio.file.InvalidPathException e) {
            own = null;
        }
        if (own != null && Files.isDirectory(own)) {
            return load(own, shown);
        }
        if (NAME.matcher(value).matches() && Files.isDirectory(bundledRoot().resolve(value))) {
            return load(bundledRoot().resolve(value), shown);
        }
        throw new ConfigException(shown + ": theme \"" + value + "\" is neither a directory (" + own
                + ") nor one of the themes Paperband brings: " + names());
    }

    /** The names of the themes Paperband brings, sorted. */
    public static List<String> names() throws IOException {
        try (Stream<Path> dirs = Files.list(bundledRoot())) {
            return dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString().replace("/", "")).sorted().toList();
        }
    }

    /** Where the bundled themes are: a directory of classes, or one inside the jar this class came from. */
    private static synchronized Path bundledRoot() throws IOException {
        if (bundled == null) {
            java.net.URL part = Theme.class.getResource(BUILT_IN + Part.WEB.file());
            if (part == null) {
                throw new IllegalStateException("built-in theme part missing: " + Part.WEB.file());
            }
            try {
                java.net.URI uri = part.toURI();
                if (uri.getScheme().equals("jar")) {
                    try {
                        java.nio.file.FileSystems.newFileSystem(uri, Map.of());
                    } catch (java.nio.file.FileSystemAlreadyExistsException e) {
                        // opened earlier, by this or another class loader: use it
                    }
                }
                bundled = Path.of(uri).getParent().getParent();
            } catch (java.net.URISyntaxException e) {
                throw new IOException(e);
            }
        }
        return bundled;
    }

    /**
     * The theme in a directory: the built-in one, with the directory's parts in place of its own.
     *
     * @param shown how to refer to the directory in messages
     */
    public static Theme load(Path dir, String shown) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new ConfigException(shown + ": theme directory not found: " + dir);
        }
        List<String> known = Stream.concat(Arrays.stream(Part.values()).map(Part::file), Stream.of(EXTRA)).toList();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.sorted().toList()) {
                String name = f.getFileName().toString();
                if (name.endsWith(".css") && !known.contains(name)) {
                    throw new ConfigException(shown + ": theme " + dir + ": " + name + " isn't a part of a theme; the parts are "
                            + known);
                }
            }
        }
        Map<Part, String> parts = new EnumMap<>(DEFAULT.parts);
        for (Part part : Part.values()) {
            Path file = dir.resolve(part.file());
            if (Files.isRegularFile(file)) {
                parts.put(part, inline(Files.readString(file, StandardCharsets.UTF_8), dir));
            }
        }
        Path extra = dir.resolve(EXTRA);
        Path layouts = dir.resolve(LAYOUTS);
        return new Theme(parts, Files.isRegularFile(extra) ? inline(Files.readString(extra, StandardCharsets.UTF_8), dir) : "",
                Files.isDirectory(layouts) ? layouts : null);
    }

    private static final java.util.regex.Pattern URL = java.util.regex.Pattern.compile(
            "url\\(\\s*(['\"]?)([^'\")]+)\\1\\s*\\)");

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("woff2", "font/woff2"), Map.entry("woff", "font/woff"), Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"), Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
            Map.entry("svg", "image/svg+xml"));

    /** {@code css} with each {@code url(...)} of a file inside {@code dir} replaced by that file as a data URL. */
    private static String inline(String css, Path dir) throws IOException {
        Path root = dir.toAbsolutePath().normalize();
        java.util.regex.Matcher m = URL.matcher(css);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String target = m.group(2).strip();
            String replacement = m.group();
            // A scheme (data:, https:), a root-relative path or a fragment isn't a file of the theme.
            if (!target.contains(":") && !target.startsWith("/") && !target.startsWith("#")) {
                Path file;
                try {
                    file = root.resolve(target).normalize();
                } catch (java.nio.file.InvalidPathException e) {
                    file = null;
                }
                if (file != null && file.startsWith(root) && Files.isRegularFile(file)) {
                    String name = file.getFileName().toString();
                    String type = TYPES.getOrDefault(name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT),
                            "application/octet-stream");
                    replacement = "url(\"data:" + type + ";base64,"
                            + java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(file)) + "\")";
                }
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** A part's CSS, ending with a newline. */
    public String css(Part part) {
        String css = parts.get(part);
        return css.endsWith("\n") ? css : css + "\n";
    }

    /** The rules added after the parts ({@value #EXTRA}), ending with a newline; empty if there are none. */
    public String extra() {
        return extra.isEmpty() || extra.endsWith("\n") ? extra : extra + "\n";
    }

    /** The directory of the theme's layouts, or null if it has none. */
    public Path layouts() {
        return layouts;
    }
}
