package dev.noregressions.paperband;

import dev.noregressions.paperband.fit.Problem;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Where one output's Markdown images are. An image's path is relative to the Markdown file it's
 * written in, as on GitHub and in an editor's preview, wherever the output is written:
 *
 * <pre>
 * docs/intro.md:   ![The cover](../images/cover.svg)      the project's images/cover.svg
 * </pre>
 *
 * <ul>
 *   <li>A web output gets a copy of each image beside it, at the image's path in the project
 *       ({@code images/cover.svg}; {@code images/NAME} for one from outside the project), so the
 *       site stands alone: {@link #copies()}. An image already under the output's directory is
 *       used where it is.</li>
 *   <li>A paged output (pdf, paged-html) links each image where it is, by a path relative to the
 *       output's directory. Nothing is copied.</li>
 *   <li>An image that isn't there is left as written and remembered: {@link #missing()}.</li>
 * </ul>
 * Left as written: anything that isn't a relative path (a URL, {@code data:}, a path from
 * {@code /}), and {@code <img>} in raw HTML, which stays relative to the output's directory.
 */
public final class Images {

    /** A scheme ({@code https:}, {@code data:}), a host, a path from the root, or only a fragment or query. */
    private static final Pattern NOT_RELATIVE = Pattern.compile("(?s)(?:[a-zA-Z][a-zA-Z0-9+.-]*:|/|#|\\?).*");

    /** The directory a copy goes in when its image is outside the project. */
    private static final String OUTSIDE = "images";

    /**
     * An image that isn't there.
     *
     * @param source the Markdown file, as the manifest names it
     * @param src    the image's path as written
     * @param looked the file it names
     */
    public record Missing(Path source, String src, Path looked) {

        /** E.g. {@code "../images/cover.svg" not found (looked in /project/images/cover.svg)}. */
        public String detail() {
            return "\"" + src + "\" not found (looked in " + looked + ")";
        }

        /** E.g. {@code docs/intro.md: image "../images/cover.svg" not found (looked in ...)}. */
        public String message() {
            return source.toString().replace('\\', '/') + ": image " + detail();
        }
    }

    private final Path root;
    private final Path outputDir;
    private final boolean copy;
    private final Map<Path, Path> copies = new LinkedHashMap<>();
    private final Map<Path, Path> targets = new HashMap<>();
    private final List<Missing> missing = new ArrayList<>();
    private final Map<String, String> written = new HashMap<>();

    /**
     * @param root      the manifest's directory: what the sources' paths are relative to
     * @param outputDir the directory the output is written to
     * @param copy      copy the images beside the output (web), rather than link them where they are
     */
    public Images(Path root, Path outputDir, boolean copy) {
        this.root = root.toAbsolutePath().normalize();
        this.outputDir = outputDir.toAbsolutePath().normalize();
        this.copy = copy;
    }

    /** Whether an image's {@code src} is a relative path, and so names a file beside something. */
    public static boolean relative(String src) {
        return src != null && !src.isEmpty() && !NOT_RELATIVE.matcher(src).matches();
    }

    /**
     * The {@code src} for an image written as {@code src} in {@code source}: relative to the
     * output's directory, or as written if it isn't a relative path or its file isn't there.
     */
    String src(Path source, String src) {
        if (source == null || !relative(src)) {
            return src;
        }
        int cut = src.indexOf('#') < 0 ? src.indexOf('?') : src.indexOf('?') < 0 ? src.indexOf('#')
                : Math.min(src.indexOf('#'), src.indexOf('?'));
        String path = cut < 0 ? src : src.substring(0, cut);
        Path file;
        try {
            Path dir = root.resolve(source).normalize().getParent();
            file = dir.resolve(URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8)).normalize();
        } catch (IllegalArgumentException e) { // an InvalidPathException too
            return src;
        }
        if (!Files.isRegularFile(file)) {
            if (missing.stream().noneMatch(m -> m.source().equals(source) && m.src().equals(src))) {
                missing.add(new Missing(source, src, file));
            }
            return src;
        }
        String url;
        if (copy) {
            url = url(target(file));
        } else {
            try {
                url = url(outputDir.relativize(file));
            } catch (IllegalArgumentException e) { // on another drive: no relative path leads there
                url = file.toUri().toString();
            }
        }
        String out = url + (cut < 0 ? "" : src.substring(cut));
        written.putIfAbsent(out, src);
        return out;
    }

    /** Where {@code file}'s copy goes, relative to the output's directory; or where it already is, if it's under it. */
    private Path target(Path file) {
        Path target = targets.get(file);
        if (target == null) {
            if (file.startsWith(outputDir)) {
                target = outputDir.relativize(file);
            } else {
                target = file.startsWith(root) ? root.relativize(file) : Path.of(OUTSIDE).resolve(file.getFileName());
                String name = target.getFileName().toString();
                int dot = name.lastIndexOf('.');
                for (int n = 2; copies.containsKey(target); n++) {
                    target = target.resolveSibling(dot > 0 ? name.substring(0, dot) + "-" + n + name.substring(dot)
                            : name + "-" + n);
                }
                copies.put(target, file);
            }
            targets.put(file, target);
        }
        return target;
    }

    private static String url(Path path) {
        return path.toString().replace('\\', '/').replace("%", "%25").replace(" ", "%20").replace("#", "%23")
                .replace("?", "%3F");
    }

    /** The files to copy beside the output: where to, relative to the output's directory, and from where. */
    public Map<Path, Path> copies() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(copies));
    }

    /** The images that aren't there, each once, in the order met. */
    public List<Missing> missing() {
        return List.copyOf(missing);
    }

    /**
     * {@code problems}, saying of each image the browser couldn't load because its file isn't
     * there that it wasn't found, and where it was looked for; any other image that didn't load
     * is named as the Markdown names it.
     */
    public List<Problem> explain(List<Problem> problems) {
        List<Problem> out = new ArrayList<>();
        for (Problem p : problems) {
            Missing found = p.kind() != Problem.Kind.IMAGE ? null : missing.stream()
                    .filter(m -> m.src().equals(p.detail()))
                    .min((a, b) -> Boolean.compare(!a.source().equals(p.source()), !b.source().equals(p.source())))
                    .orElse(null);
            if (found != null) {
                out.add(new Problem(p.page(), p.source(), p.box(), Problem.Kind.IMAGE_NOT_FOUND, 0, found.detail(), p.tried()));
            } else if (p.kind() == Problem.Kind.IMAGE && written.containsKey(p.detail())) { // as the Markdown has it
                out.add(new Problem(p.page(), p.source(), p.box(), p.kind(), 0, written.get(p.detail()), p.tried()));
            } else {
                out.add(p);
            }
        }
        return List.copyOf(out);
    }
}
