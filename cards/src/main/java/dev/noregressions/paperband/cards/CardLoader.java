package dev.noregressions.paperband.cards;

import com.vladsch.flexmark.ext.attributes.AttributesExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.misc.Extension;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;
import dev.noregressions.paperband.model.Frontmatter;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads a markdown card into a {@link Card} model.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Split YAML frontmatter from body via regex.</li>
 *   <li>Parse frontmatter via SnakeYAML for full type fidelity (lists, maps, numbers, bools).</li>
 *   <li>Render body to HTML via flexmark with {@code AttributesExtension} (so that
 *       {@code ## Watch Out {.watch-out #wo-1}} attaches class/id to the {@code h2})
 *       and {@code TablesExtension} (GFM pipe tables).</li>
 *   <li>Parse that HTML with jsoup; walk top-level children.</li>
 *   <li>{@code h1} (if any) becomes the card title (frontmatter {@code title:} wins if set).</li>
 *   <li>Every other heading level ({@code h2}–{@code h6}) opens a new
 *       {@link Block.Kind#HEADING_SECTION HEADING_SECTION} block, nested by rank — see below.
 *       Class set comes from the heading's {@code class} attribute, falling back to a slug
 *       derived from the heading text.</li>
 *   <li>Content before the first real heading becomes a synthetic top-level block with
 *       class {@code "intro"}.</li>
 * </ol>
 *
 * <p><b>Nesting by heading rank.</b> A heading at level <i>L</i> closes every
 * currently-open section whose level is <i>L</i> or deeper, then opens a new
 * section nested under whichever section (if any) is still open above it.
 * This is the standard rank-based sectioning rule (the same one Pandoc's
 * {@code --section-divs} and Docutils' section transform use): an {@code h3}
 * nests under the preceding {@code h2}, a following {@code h2} closes both
 * the {@code h3} and the {@code h2} it belonged to, and a document that only
 * ever uses {@code h2} behaves exactly as a single flat list, unchanged from
 * before nesting existed. See {@link Block}'s javadoc for the resulting
 * model shape.
 *
 * <p><b>v0.5 limitation</b> (expected to land in v0.6): Pandoc fenced divs
 * ({@code :::{.note}} ... {@code :::}) are not yet extracted. They render
 * through as literal text in the body HTML.
 *
 * <p>Conditional sections don't need block-level support here at all: wrap
 * the section in real {@code {% if vars.x %} ... {% endif %}} and the
 * whole-body Pebble pass ({@code PebbleIncludePreprocessor} in
 * {@code pagewright-include}) removes it from the source before this class
 * ever sees it.
 */
public final class CardLoader {

    /** Match a YAML frontmatter block at the very start of the file. */
    private static final Pattern FRONTMATTER =
            Pattern.compile("\\A---\\s*\\R(.*?)\\R---\\s*(?:\\R|\\z)", Pattern.DOTALL);

    private final Parser parser;
    private final HtmlRenderer renderer;
    private final MarkdownPreprocessor preprocessor;

    /** Construct a loader with no markdown preprocessor. */
    public CardLoader() {
        this(null);
    }

    /**
     * Construct a loader with an optional pre-flexmark markdown preprocessor.
     * The hook lets the include subsystem (or any other pre-flexmark pass) run
     * before frontmatter parsing and HTML rendering.
     */
    public CardLoader(MarkdownPreprocessor preprocessor) {
        this.preprocessor = preprocessor;
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS,
                List.<Extension>of(AttributesExtension.create(), TablesExtension.create()));
        this.parser   = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();
    }

    /**
     * Read {@code mdFile} from disk, run the preprocessor (if any) and parse the
     * result into a {@link Card}.
     *
     * @throws CardParseException if the file cannot be read or the markdown is malformed
     */
    public Card load(Path mdFile) {
        try {
            String source = Files.readString(mdFile);
            if (preprocessor != null) {
                source = preprocessor.process(source, mdFile);
            }
            return parse(mdFile, source);
        } catch (IOException e) {
            throw new CardParseException("Failed to read " + mdFile, e);
        }
    }

    /**
     * Parse {@code markdown} as if it had been read from {@code source}. The
     * supplied path is recorded on the resulting {@link Card} and used in error
     * messages; it does not need to exist on disk.
     *
     * @throws CardParseException if the frontmatter or body is malformed
     */
    public Card parse(Path source, String markdown) {
        // 1. Frontmatter / body split
        Frontmatter fm = Frontmatter.empty();
        String body = markdown;
        Matcher m = FRONTMATTER.matcher(markdown);
        if (m.find()) {
            fm   = parseFrontmatter(source, m.group(1));
            body = markdown.substring(m.end());
        }

        // 2. Markdown → HTML via flexmark
        com.vladsch.flexmark.util.ast.Node doc = parser.parse(body);
        String html = renderer.render(doc);

        // 3. Walk via jsoup
        org.jsoup.nodes.Document jdoc = Jsoup.parseBodyFragment(html);
        Element bodyEl = jdoc.body();

        // 3a. Rewrite custom fenced-code conventions (diff-card, error-output)
        //     into structured HTML before block-walking. Real-language fences
        //     pass through untouched for Prism to highlight downstream.
        try {
            DiffCardProcessor.process(bodyEl);
        } catch (IllegalArgumentException e) {
            throw new CardParseException(source + ": " + e.getMessage(), e);
        }

        // 3b. Classify inline <code> spans by shape (annotation, type, method,
        //     property, etc.) so themes can colour them semantically. Code
        //     inside <pre> is left alone — Prism handles those.
        InlineCodeClassifier.process(bodyEl);

        String title = fm.getString("title").orElse(null);
        List<Block> topLevel = new ArrayList<>();
        Deque<OpenSection> stack = new ArrayDeque<>();
        StringBuilder introHtml = new StringBuilder();
        boolean introFlushed = false;

        for (Node child : bodyEl.childNodes()) {
            if (child instanceof Element el) {
                String tag = el.tagName();
                int level = headingLevel(tag);
                if (level == 1) {
                    if (title == null || title.isBlank()) {
                        title = el.text();
                    }
                    continue;
                }
                if (level >= 2) {
                    // A heading at this level closes every section at this
                    // level or deeper -- see the class javadoc for why.
                    while (!stack.isEmpty() && stack.peek().level >= level) {
                        closeSection(stack.pop(), stack, topLevel);
                    }
                    if (!introFlushed) {
                        introFlushed = true;
                        String introContent = introHtml.toString();
                        if (!introContent.isBlank()) {
                            topLevel.add(new Block(
                                    Block.Kind.HEADING_SECTION, null, Set.of("intro"),
                                    null, 0, introContent, List.of()));
                        }
                    }
                    OpenSection section = new OpenSection();
                    section.level    = level;
                    section.heading  = el.text();
                    section.id       = el.id().isEmpty() ? null : el.id();
                    section.classes  = parseClassAttr(el.className());
                    stack.push(section);
                    continue;
                }
                appendContent(stack, introHtml, el.outerHtml());
                continue;
            }
            if (child instanceof TextNode tn) {
                String text = tn.text();
                if (!text.isBlank()) appendContent(stack, introHtml, text);
            }
        }
        // Close whatever's still open, innermost first.
        while (!stack.isEmpty()) {
            closeSection(stack.pop(), stack, topLevel);
        }
        // No heading ever appeared -- any content is a lone intro block.
        if (!introFlushed) {
            String introContent = introHtml.toString();
            if (!introContent.isBlank()) {
                topLevel.add(new Block(
                        Block.Kind.HEADING_SECTION, null, Set.of("intro"),
                        null, 0, introContent, List.of()));
            }
        }

        String id = fm.getString("id").orElseGet(() -> deriveIdFromFile(source));
        validateId(id, source);
        return new Card(id, source, fm, title, topLevel);
    }

    /**
     * Card ids are used as single path segments when writing site output
     * ({@code cards/<id>.html}), so they must not be able to traverse out of
     * the output directory. Rejects separators, {@code .}/{@code ..}, blanks,
     * and control characters.
     */
    private static void validateId(String id, Path source) {
        if (id == null || id.isBlank()) {
            throw new CardParseException(source + ": card id must not be blank");
        }
        if (id.indexOf('/') >= 0 || id.indexOf('\\') >= 0
                || id.equals(".") || id.equals("..")
                || id.chars().anyMatch(c -> c < 0x20)) {
            throw new CardParseException(
                    source + ": invalid card id '" + id
                            + "': ids are used as file names and must not contain"
                            + " path separators, '..', or control characters");
        }
    }

    // ----------- helpers -----------

    @SuppressWarnings("unchecked")
    private Frontmatter parseFrontmatter(Path source, String yaml) {
        try {
            Object loaded = new Yaml().load(yaml);
            if (loaded == null) return Frontmatter.empty();
            if (loaded instanceof Map<?, ?> map) {
                return new Frontmatter((Map<String, Object>) map);
            }
            throw new CardParseException(source + ": frontmatter must be a YAML mapping");
        } catch (CardParseException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CardParseException(
                    source + ": frontmatter parse failed: " + e.getMessage(), e);
        }
    }

    /** A heading section still being accumulated, before it closes and becomes an immutable {@link Block}. */
    private static final class OpenSection {
        int level;
        String heading;
        String id;
        Set<String> classes;
        final StringBuilder html = new StringBuilder();
        final List<Block> children = new ArrayList<>();
    }

    /** {@code h1}→1, {@code h2}–{@code h6}→2–6, anything else→-1 (not a heading). */
    private static int headingLevel(String tag) {
        if (tag.length() == 2 && tag.charAt(0) == 'h') {
            char c = tag.charAt(1);
            if (c >= '1' && c <= '6') return c - '0';
        }
        return -1;
    }

    /**
     * Append ordinary (non-heading) content to whichever section is
     * currently innermost, or to the pre-first-heading intro buffer when
     * nothing is open yet. The stack is only ever empty before the first
     * heading and after the final close at end-of-document -- once any
     * section is open, it stays open (something is always on top) until the
     * next heading closes and replaces it, so this check alone is enough to
     * route content correctly throughout the walk.
     */
    private static void appendContent(Deque<OpenSection> stack, StringBuilder introHtml, String content) {
        if (stack.isEmpty()) {
            introHtml.append(content);
        } else {
            stack.peek().html.append(content);
        }
    }

    /** Close {@code section}, attaching it as a child of the new stack top, or as a top-level block if none remains. */
    private static void closeSection(OpenSection section, Deque<OpenSection> stack, List<Block> topLevel) {
        Block block = build(section);
        if (stack.isEmpty()) {
            topLevel.add(block);
        } else {
            stack.peek().children.add(block);
        }
    }

    private static Block build(OpenSection section) {
        Set<String> finalClasses = new LinkedHashSet<>(section.classes);
        if (finalClasses.isEmpty()) {
            String slug = slug(section.heading);
            if (!slug.isEmpty()) finalClasses.add(slug);
        }
        return new Block(
                Block.Kind.HEADING_SECTION,
                section.id,
                finalClasses,
                section.heading,
                section.level,
                section.html.toString(),
                section.children);
    }

    private static Set<String> parseClassAttr(String classAttr) {
        Set<String> out = new LinkedHashSet<>();
        if (classAttr == null || classAttr.isBlank()) return out;
        for (String c : classAttr.split("\\s+")) {
            if (!c.isEmpty()) out.add(c);
        }
        return out;
    }

    private static String slug(String text) {
        if (text == null) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
            else if (c == '-' || c == '_' || Character.isWhitespace(c)) sb.append('-');
        }
        // collapse repeats and trim
        String s = sb.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
        return s;
    }

    private static String deriveIdFromFile(Path source) {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
