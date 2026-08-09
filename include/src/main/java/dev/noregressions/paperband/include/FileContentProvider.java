package dev.noregressions.paperband.include;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link ContentProvider} that reads fragments from local files.
 *
 * <h2>Reference syntax</h2>
 * <pre>
 *   path                  → entire file
 *   path:tag              → region between {@code ANCHOR: tag} and {@code ANCHOR_END: tag}
 *   path:start:end        → lines start..end inclusive (1-indexed)
 * </pre>
 *
 * <h2>Path resolution</h2>
 * <ol>
 *   <li>If {@code path} is absolute and exists, use it verbatim.</li>
 *   <li>Resolve against the markdown file's directory (when supplied).</li>
 *   <li>Walk the {@code paths} list from {@code providerConfig}, anchoring
 *       relative entries at the book root if available.</li>
 *   <li>Fall back to the book root.</li>
 * </ol>
 * Whichever resolution succeeds first wins. Failure to resolve raises a
 * {@link ContentResolutionException} with the search path listed.
 *
 * <h2>Containment</h2>
 * A resolved path must live inside one of the allowed roots — the book root,
 * the including card's directory, or a base listed in the provider's
 * {@code paths} config — or match an explicit operator-supplied allowance.
 * Absolute references and {@code ..} traversal that land outside every allowed
 * location are rejected, so a book obtained from a third party cannot splice
 * arbitrary local files (e.g. {@code /etc/passwd} or {@code ../../.ssh/id_rsa})
 * into the output.
 *
 * <p>The trust decision to reach outside the book root belongs to the operator
 * running the build, not to the book being built. It is therefore expressed as
 * explicit allow-lists in the provider config, never as a blanket switch that a
 * book could set for itself:
 * <ul>
 *   <li>{@code external_roots} — list of directories whose subtree is permitted;</li>
 *   <li>{@code external_files} — list of individual files permitted verbatim.</li>
 * </ul>
 * Both are resolved to real paths (symlinks followed) before comparison.
 *
 * <h2>Anchor markers</h2>
 * Default {@code ANCHOR:} / {@code ANCHOR_END:}; override per-directive with
 * the {@code marker} attribute (single keyword used to derive both, e.g.
 * {@code marker=BEGIN} → {@code BEGIN:} / {@code BEGIN_END:}). The matcher
 * ignores the surrounding comment characters, so the same regex catches
 * {@code //}, {@code #}, {@code <!-- -->} and similar without configuration.
 *
 * <h2>Captured snippet processing</h2>
 * <ul>
 *   <li>Marker lines are dropped from the captured region (including markers
 *       for unrelated anchors that happen to be nested inside).</li>
 *   <li>Common leading whitespace is stripped via {@link Dedent}.</li>
 * </ul>
 */
public final class FileContentProvider implements ContentProvider {

    public static final String NAME = "file";

    private static final String DEFAULT_ANCHOR_KEYWORD = "ANCHOR";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Fragment fetch(String reference, IncludeContext ctx) throws ContentResolutionException {
        FileRef ref = FileRef.parse(reference);
        Path resolved = resolvePath(ref.path(), ctx);

        List<String> allLines;
        try {
            allLines = Files.readAllLines(resolved, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ContentResolutionException(
                    "file include: failed to read " + resolved + ": " + e.getMessage(), e);
        }

        String anchorKeyword = ctx.attributes().getOrDefault("marker", DEFAULT_ANCHOR_KEYWORD);
        List<String> captured = switch (ref.selector()) {
            case Selector.None ignored -> allLines;
            case Selector.LineRange r  -> sliceLines(resolved, allLines, r.start(), r.end());
            case Selector.Anchor a     -> extractAnchor(resolved, allLines, a.name(), anchorKeyword);
        };

        boolean dedent = !"false".equalsIgnoreCase(ctx.attributes().get("dedent"));
        String content = dedent ? Dedent.dedent(captured) : String.join("\n", captured);

        String ext = extensionOf(ref.path());
        Optional<String> language = Languages.languageFor(ext);
        String mediaType = mediaTypeFor(ext, language);
        String origin = resolved + describeSelector(ref.selector());

        return new Fragment(content, mediaType, language, Optional.of(origin));
    }

    @Override
    public void validate(String reference, IncludeContext ctx) throws ContentResolutionException {
        FileRef ref = FileRef.parse(reference);
        Path resolved = resolvePath(ref.path(), ctx);
        if (!Files.isRegularFile(resolved)) {
            throw new ContentResolutionException(
                    "file include: not a regular file: " + resolved);
        }
        // For anchor or line-range refs, a deeper check requires reading the file.
        // Defer to fetch() in that case so we exercise the same code path.
        if (!(ref.selector() instanceof Selector.None)) {
            fetch(reference, ctx);
        }
    }

    // ---- path resolution ----

    private static Path resolvePath(String pathStr, IncludeContext ctx) throws ContentResolutionException {
        Path requested = Path.of(pathStr);
        List<Path> tried = new ArrayList<>();
        List<Path> allowedRoots = allowedRoots(ctx);

        if (requested.isAbsolute()) {
            tried.add(requested);
            if (!Files.isRegularFile(requested)) {
                throw new ContentResolutionException(
                        "file include: absolute path not found: " + requested);
            }
            return checkContained(requested, allowedRoots, ctx, pathStr);
        }

        if (ctx.sourceFile() != null) {
            Path parent = ctx.sourceFile().toAbsolutePath().getParent();
            if (parent != null) {
                Path candidate = parent.resolve(requested).normalize();
                tried.add(candidate);
                if (Files.isRegularFile(candidate)) {
                    return checkContained(candidate, allowedRoots, ctx, pathStr);
                }
            }
        }

        Object pathsCfg = ctx.providerConfig().get("paths");
        if (pathsCfg instanceof List<?> list) {
            for (Object pathStrCfg : list) {
                Path base = Path.of(String.valueOf(pathStrCfg));
                Path resolvedBase = base.isAbsolute()
                        ? base
                        : (ctx.bookRoot() != null ? ctx.bookRoot().resolve(base) : base);
                Path candidate = resolvedBase.resolve(requested).normalize();
                tried.add(candidate);
                if (Files.isRegularFile(candidate)) {
                    return checkContained(candidate, allowedRoots, ctx, pathStr);
                }
            }
        }

        if (ctx.bookRoot() != null) {
            Path candidate = ctx.bookRoot().resolve(requested).normalize();
            tried.add(candidate);
            if (Files.isRegularFile(candidate)) {
                return checkContained(candidate, allowedRoots, ctx, pathStr);
            }
        }

        throw new ContentResolutionException(
                "file include: not found: " + pathStr
                        + " (tried: " + tried + ")");
    }

    // ---- containment ----

    /**
     * Roots an include is allowed to resolve inside: the book root, the
     * including card's directory, and every base declared in the provider's
     * {@code paths} config (those are explicit, user-auditable declarations
     * in {@code pagewright.yaml}).
     */
    private static List<Path> allowedRoots(IncludeContext ctx) {
        List<Path> roots = new ArrayList<>();
        if (ctx.bookRoot() != null) {
            roots.add(ctx.bookRoot().toAbsolutePath().normalize());
        }
        if (ctx.sourceFile() != null) {
            Path parent = ctx.sourceFile().toAbsolutePath().getParent();
            if (parent != null) roots.add(parent.normalize());
        }
        if (ctx.providerConfig().get("paths") instanceof List<?> list) {
            for (Object entry : list) {
                Path base = Path.of(String.valueOf(entry));
                Path resolved = base.isAbsolute()
                        ? base
                        : (ctx.bookRoot() != null ? ctx.bookRoot().resolve(base) : base.toAbsolutePath());
                roots.add(resolved.normalize());
            }
        }
        // Operator-supplied external directories (e.g. --external-include-dir).
        if (ctx.providerConfig().get("external_roots") instanceof List<?> ext) {
            for (Object entry : ext) {
                roots.add(Path.of(String.valueOf(entry)).toAbsolutePath().normalize());
            }
        }
        return roots;
    }

    /**
     * Reject a resolved file that escapes every allowed location. A candidate is
     * permitted if it lives under an allowed root (book root, card directory,
     * configured {@code paths} or {@code external_roots}) or exactly matches one
     * of the operator-supplied {@code external_files}. Symlinks in the candidate
     * and every allowed location are resolved before comparing, so a symlink
     * inside the book pointing outside it cannot bypass the check.
     */
    private static Path checkContained(Path candidate, List<Path> allowedRoots,
                                       IncludeContext ctx, String pathStr)
            throws ContentResolutionException {
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException e) {
            throw new ContentResolutionException(
                    "file include: cannot resolve " + candidate + ": " + e.getMessage(), e);
        }
        for (Path root : allowedRoots) {
            Path realRoot;
            try {
                realRoot = root.toRealPath();
            } catch (IOException e) {
                continue; // root doesn't exist; can't contain anything
            }
            if (real.startsWith(realRoot)) return candidate;
        }
        // Individually allow-listed files (e.g. --external-include-file).
        if (ctx.providerConfig().get("external_files") instanceof List<?> files) {
            for (Object f : files) {
                try {
                    if (real.equals(Path.of(String.valueOf(f)).toRealPath())) return candidate;
                } catch (IOException ignored) {
                    // listed file doesn't exist; skip
                }
            }
        }
        throw new ContentResolutionException(
                "file include: '" + pathStr + "' resolves to " + real
                        + ", outside the book root and allowed include locations."
                        + " Permit it by adding the location to the file provider's 'paths'"
                        + " config, or by passing --external-include-dir=<dir> /"
                        + " --external-include-file=<file> to the build.");
    }

    // ---- selector application ----

    private static List<String> sliceLines(Path source, List<String> lines, int start, int end)
            throws ContentResolutionException {
        if (start < 1 || end < start || end > lines.size()) {
            throw new ContentResolutionException(
                    "file include: line range " + start + ":" + end
                            + " out of bounds for " + source + " (1.." + lines.size() + ")");
        }
        return new ArrayList<>(lines.subList(start - 1, end));
    }

    /**
     * Extract the lines between {@code KEYWORD: name} and {@code KEYWORD_END: name},
     * stripping any other marker lines that happen to be inside the captured region.
     * Marker matching is comment-character-agnostic.
     */
    static List<String> extractAnchor(Path source, List<String> lines, String name, String keyword)
            throws ContentResolutionException {
        Pattern startPattern = Pattern.compile(
                ".*\\b" + Pattern.quote(keyword) + ":\\s*" + Pattern.quote(name) + "\\b.*");
        Pattern endPattern = Pattern.compile(
                ".*\\b" + Pattern.quote(keyword) + "_END:\\s*" + Pattern.quote(name) + "\\b.*");
        Pattern anyMarker = Pattern.compile(
                ".*\\b" + Pattern.quote(keyword) + "(?:_END)?:\\s*[A-Za-z0-9_-]+\\b.*");

        int startIdx = -1;
        int endIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (startIdx < 0 && startPattern.matcher(line).matches()) {
                startIdx = i;
            } else if (startIdx >= 0 && endPattern.matcher(line).matches()) {
                endIdx = i;
                break;
            }
        }
        if (startIdx < 0) {
            throw new ContentResolutionException(
                    "file include: anchor '" + name + "' not found in " + source
                            + " (looking for '" + keyword + ": " + name + "')");
        }
        if (endIdx < 0) {
            throw new ContentResolutionException(
                    "file include: anchor '" + name + "' has no matching '"
                            + keyword + "_END: " + name + "' in " + source);
        }
        List<String> out = new ArrayList<>(endIdx - startIdx);
        for (int i = startIdx + 1; i < endIdx; i++) {
            String line = lines.get(i);
            if (!anyMarker.matcher(line).matches()) {
                out.add(line);
            }
        }
        return out;
    }

    // ---- helpers ----

    private static String extensionOf(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot < slash) return "";
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String mediaTypeFor(String ext, Optional<String> language) {
        return switch (ext) {
            case "md", "markdown" -> "text/markdown";
            case "html", "htm" -> "text/html";
            case "txt" -> "text/plain";
            default -> language.map(l -> "text/x-" + l).orElse("text/plain");
        };
    }

    private static String describeSelector(Selector s) {
        return switch (s) {
            case Selector.None ignored -> "";
            case Selector.LineRange r -> ":" + r.start() + ":" + r.end();
            case Selector.Anchor a -> ":" + a.name();
        };
    }

    // ---- types ----

    /** Parsed file reference: a path plus an optional in-file selector. */
    record FileRef(String path, Selector selector) {

        /** Match {@code path:N:M} where N and M are integers. */
        private static final Pattern LINE_RANGE = Pattern.compile("^(.+):(\\d+):(\\d+)$");

        /** Match {@code path:tag} where {@code tag} is alphanumeric/dash/underscore and starts with a letter. */
        private static final Pattern ANCHOR = Pattern.compile("^(.+):([A-Za-z][A-Za-z0-9_-]*)$");

        static FileRef parse(String reference) {
            Matcher rng = LINE_RANGE.matcher(reference);
            if (rng.matches()) {
                return new FileRef(rng.group(1),
                        new Selector.LineRange(
                                Integer.parseInt(rng.group(2)),
                                Integer.parseInt(rng.group(3))));
            }
            Matcher anc = ANCHOR.matcher(reference);
            if (anc.matches()) {
                return new FileRef(anc.group(1), new Selector.Anchor(anc.group(2)));
            }
            return new FileRef(reference, new Selector.None());
        }
    }

    sealed interface Selector permits Selector.None, Selector.Anchor, Selector.LineRange {
        record None() implements Selector {}
        record Anchor(String name) implements Selector {}
        record LineRange(int start, int end) implements Selector {}
    }
}
