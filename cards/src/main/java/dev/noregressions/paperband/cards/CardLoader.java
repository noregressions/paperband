package dev.noregressions.paperband.cards;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

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
import java.util.LinkedHashMap;
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
 *   <li>Render body to HTML via commonmark-java with GFM pipe tables and
 *       paperband's own attribute syntax — {@link AttributeSyntax}, so that
 *       {@code ## Watch Out {.watch-out id=wo-1}} attaches class/id to the {@code h2}.</li>
 *   <li>{@link Sections} makes the heading structure explicit in the tree:
 *       each heading and everything it owns become a {@code <section>}, nested
 *       by rank (see below). Parse the HTML with jsoup and read those sections
 *       as blocks. An {@code .html} card has no markdown tree, so its blocks are
 *       inferred from the headings instead, by the same rule.</li>
 *   <li>A frontmatter {@code title:} names the card. Without one, the first
 *       {@code h1} does instead, and is consumed rather than rendered — so a
 *       card shows one heading, not a title plus a copy of it. With one, no
 *       {@code h1} is consumed: the card is already named, and every heading the
 *       author wrote renders.</li>
 *   <li>Every heading that isn't consumed as the title — {@code h2}–{@code h6},
 *       and any {@code h1} — opens a new
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
 * {@code include}) removes it from the source before this class
 * ever sees it.
 */
public final class CardLoader {

    /**
     * Match a YAML frontmatter block at the very start of the file.
     *
     * <p>The closing fence is anchored to the start of a line ({@code ^} under
     * {@code MULTILINE}) rather than written as "a line break then {@code ---}".
     * The difference is an <em>empty</em> block:
     *
     * <pre>
     * ---
     * ---
     * # Title
     * </pre>
     *
     * <p>which an authoring tool writes when it has no metadata to add, and a
     * hand-edited card is left with once its last key goes. Requiring a line
     * break before the closing fence meant the opening fence's own newline had
     * already been consumed, so the second {@code ---} was read as content and
     * the scan ran on to the <em>next</em> {@code ---} in the document — parsing
     * the prose in between as YAML, and failing on whatever punctuation YAML
     * happens to reserve.
     *
     * <p>{@code [ \t]*} rather than {@code \s*} around the fences for the same
     * reason: {@code \s} matches line breaks, so it could swallow the very
     * boundary the pattern is trying to find.
     */
    private static final Pattern FRONTMATTER = Pattern.compile(
            "\\A---[ \\t]*\\R(.*?)^---[ \\t]*(?:\\R|\\z)",
            Pattern.DOTALL | Pattern.MULTILINE);

    /**
     * The book root ids are derived relative to, or null to fall back to the
     * filename alone. See {@link #deriveIdFromFile}.
     */
    private final Path bookRoot;

    private final MarkdownPreprocessor preprocessor;

    /**
     * What happens to presentation markup in content — see
     * {@link ContentPolicy}. CLEAN by default: the tool's contract is that
     * content carries structure and the theme owns appearance. Mutable rather
     * than constructed-in because the policy rides the {@code vars} cascade,
     * so one loader serving a whole book can be re-pointed per card.
     */
    private ContentPolicy contentPolicy = ContentPolicy.CLEAN;

    /** Where CLEAN-mode removals are reported; null drops them silently. */
    private java.util.function.Consumer<String> onRemoval;

    /**
     * Renders {@code ```type} blocks through {@code blocks/<type>.html}
     * templates — see {@link BlockTemplates}. Defaults to the bundled types
     * (command, output, console); the build wires a book-aware chain so a
     * book's {@code layouts/blocks/} and the theme participate.
     */
    private BlockTemplates blockTemplates = BlockTemplates.bundled();

    /** The card's vars, exposed to block templates as {@code vars}. */
    private Map<String, Object> blockVars = Map.of();

    /**
     * Set the block-template resolver and the vars it exposes for subsequent
     * parses. Vars vary per card via the cascade, so callers re-point this
     * per card the way they do {@link #setContentPolicy}.
     *
     * @param templates the resolver; null resets to the bundled types
     * @param vars      the card's resolved vars; null is treated as empty
     */
    public void setBlockTemplates(BlockTemplates templates, Map<String, Object> vars) {
        this.blockTemplates = templates == null ? BlockTemplates.bundled() : templates;
        this.blockVars = vars == null ? Map.of() : vars;
    }

    /**
     * Set the content policy for subsequent parses, and where CLEAN-mode
     * removals are reported (each message already names the card file).
     *
     * @param policy    the policy; null resets to {@link ContentPolicy#CLEAN}
     * @param onRemoval removal sink, e.g. a build log's warn; null drops them
     */
    public void setContentPolicy(ContentPolicy policy, java.util.function.Consumer<String> onRemoval) {
        this.contentPolicy = policy == null ? ContentPolicy.CLEAN : policy;
        this.onRemoval = onRemoval;
    }

    /** Construct a loader with no markdown preprocessor. */
    public CardLoader() {
        this((MarkdownPreprocessor) null, null);
    }

    /**
     * Construct a loader with an optional pre-parse markdown preprocessor.
     * The hook lets the include subsystem (or any other pre-parse pass) run
     * before frontmatter parsing and HTML rendering.
     */
    public CardLoader(MarkdownPreprocessor preprocessor) {
        this(preprocessor, (Path) null);
    }

    /**
     * Construct a loader that derives ids from each card's path relative to
     * {@code bookRoot} — the form a book wants, since it's unique per file
     * without the author writing an {@code id:} anywhere (see
     * {@link #deriveIdFromFile}).
     *
     * @param bookRoot the book root, or null to derive ids from the filename alone
     */
    public CardLoader(Path bookRoot) {
        this((MarkdownPreprocessor) null, bookRoot);
    }

    /**
     * Construct a loader with both a preprocessor and a book root.
     *
     * @param preprocessor pre-parse pass, or null
     * @param bookRoot     the book root ids are relative to, or null
     */
    public CardLoader(MarkdownPreprocessor preprocessor, Path bookRoot) {
        this.preprocessor = preprocessor;
        this.bookRoot = bookRoot;
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
        // An .html source is a card in HTML's own idiom: <head> metadata is
        // its frontmatter, <body> its content — see parseHtmlCard.
        if (source != null && source.getFileName().toString()
                .toLowerCase(java.util.Locale.ROOT).endsWith(".html")) {
            return parseHtmlCard(source, markdown);
        }

        // 1. Frontmatter / body split
        Frontmatter fm = Frontmatter.empty();
        String body = markdown;
        Matcher m = FRONTMATTER.matcher(markdown);
        if (m.find()) {
            fm   = parseFrontmatter(source, m.group(1));
            body = markdown.substring(m.end());
        }

        // 2. Markdown → HTML via commonmark-java. The attribute syntax runs
        //    first, then Sections writes the heading structure into the tree
        //    as explicit <section> nodes -- see Sections for the rule -- and
        //    Steps numbers {!step} among the siblings that structure made.
        //    Directives runs first so a {!step} marker is never mistaken for an
        //    attribute group. Parser
        //    and renderer are built per parse because both post-processors
        //    keep per-document state (nodes by identity) until render; both
        //    are cheap to build. Heading ids appear only when the author
        //    wrote one: commonmark-java generates none of its own.
        String declaredTitle = fm.getString("title").orElse(null);
        AttributeSyntax attributeSyntax = new AttributeSyntax();
        Sections sections = new Sections(declaredTitle == null || declaredTitle.isBlank(),
                attributeSyntax);
        List<org.commonmark.Extension> extensions = List.of(TablesExtension.create());
        String html;
        String astFallback = null;
        AstDiagram.Detail ast;
        try {
            ast = AstDiagram.Detail.of(fm.get("ast").orElse(null));
        } catch (IllegalArgumentException e) {
            throw new CardParseException(source + ": " + e.getMessage(), e);
        }
        try {
            org.commonmark.node.Node doc = Parser.builder()
                    .extensions(extensions)
                    .postProcessor(new Directives())
                    .postProcessor(attributeSyntax)
                    .postProcessor(new Spans(attributeSyntax))
                    .postProcessor(sections)
                    .postProcessor(new Steps(attributeSyntax))
                    .build()
                    .parse(body);
            html = HtmlRenderer.builder()
                    .extensions(extensions)
                    .nodeRendererFactory(sections.renderer())
                    .nodeRendererFactory(Directives.renderer())
                    .nodeRendererFactory(Spans.renderer())
                    .attributeProviderFactory(context -> attributeSyntax.attributeProvider())
                    .attributeProviderFactory(context -> sections.titleMarker())
                    .build()
                    .render(doc);
            // ast: in the frontmatter draws the tree just built into the card,
            // as a last block holding a plantuml fence: the block pass below
            // draws it like any other, or leaves the source readable when no
            // PlantUML renderer is installed.
            if (ast != null) {
                AstDiagram.Item tree = AstDiagram.tree(doc, attributeSyntax, ast);
                html += astBlock(AstDiagram.plantuml(tree));
                astFallback = AstDiagram.html(tree);
            }
        } catch (IllegalArgumentException e) {
            throw new CardParseException(source + ": " + e.getMessage(), e);
        }

        // 3. The rendered sections are the blocks: read them via jsoup
        return buildCard(source, fm, Jsoup.parseBodyFragment(html).body(), true, astFallback);
    }

    /** The marked section that carries an {@code ast:} diagram. */
    private static String astBlock(String plantuml) {
        return "\n<section " + Sections.SECTION_ATTR + "=\"2\">\n"
                + "<h2 class=\"paperband-ast\">AST</h2>\n"
                + "<pre><code class=\"language-plantuml\">"
                + org.jsoup.nodes.Entities.escape(plantuml)
                + "</code></pre>\n</section>\n";
    }

    /**
     * Parse an {@code .html} source into a {@link Card}: same card concept,
     * HTML's own idiom for the metadata. The {@code <head>} <em>is</em> the
     * frontmatter — {@code <title>} maps to {@code title:} and each
     * {@code <meta name="..." content="...">} to a frontmatter field (so axis
     * values, {@code index:} terms and {@code sort:} fields all work), with
     * numeric and boolean content refined to real types the way yaml would
     * have typed them. The {@code <body>} then goes down exactly the pipeline
     * a markdown card's rendered body does: diff-card rewriting, inline-code
     * classification, the content policy, and the heading walk that splits it
     * into blocks — one card concept, two source syntaxes.
     *
     * <p>A headless fragment works too: jsoup's parser gives it an empty
     * synthetic head, the title falls back to the first {@code <h1>} exactly
     * as markdown's does, and the id derives from the filename as always.
     */
    private Card parseHtmlCard(Path source, String html) {
        org.jsoup.nodes.Document jdoc = Jsoup.parse(html == null ? "" : html);
        Map<String, Object> meta = new LinkedHashMap<>();
        String docTitle = jdoc.title();
        if (docTitle != null && !docTitle.isBlank()) meta.put("title", docTitle.trim());
        // Selected document-wide, not head-only: a fragment pasted without a
        // real <head> can carry its <meta> mid-body and still mean it.
        for (Element metaEl : jdoc.select("meta[name]")) {
            String name = metaEl.attr("name").trim();
            String content = metaEl.attr("content");
            if (!name.isEmpty() && !meta.containsKey(name)) {
                meta.put(name, refineMetaValue(content));
            }
        }
        if (AstDiagram.Detail.of(meta.get("ast")) != null) {
            throw new CardParseException(source + ": ast: needs a markdown card. An .html card"
                    + " has no markdown tree to draw.");
        }
        return buildCard(source, new Frontmatter(meta), jdoc.body(), false, null);
    }

    /**
     * Give a {@code <meta content="...">} string the type yaml would have
     * given the same spelling — integers, decimals and booleans as
     * themselves — so an HTML card's {@code tier} sorts and groups exactly
     * like a markdown card's.
     */
    private static Object refineMetaValue(String value) {
        String s = value == null ? "" : value.trim();
        try {
            if (s.matches("-?\\d+")) return Integer.valueOf(s);
            if (s.matches("-?\\d+\\.\\d+")) return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return s;   // out of range: keep the spelling
        }
        if (s.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (s.equalsIgnoreCase("false")) return Boolean.FALSE;
        return s;
    }

    /**
     * The shared back half of card parsing: everything after a body element
     * exists, whatever syntax it came from.
     *
     * @param explicitSections true when {@link Sections} has already marked the
     *        structure (a markdown card); false to infer it from the headings
     *        (an {@code .html} card, which has no markdown tree)
     * @param astFallback the {@code ast:} tree as HTML, used when no block
     *        renderer drew its PlantUML fence; null when the card has no {@code ast:}
     */
    private Card buildCard(Path source, Frontmatter fm, Element bodyEl, boolean explicitSections,
                           String astFallback) {
        // 3a-pre. Block templates: a ```type block whose type has a
        //     blocks/<type>.html template (book layouts/, theme, or bundled)
        //     renders through it — the pluggable half of what a fence means.
        //     Types with no template fall through untouched, which is also
        //     what keeps diff-card/error-output on their Java path below.
        applyBlockTemplates(source, bodyEl);

        // No PlantUML renderer drew the ast: diagram, so its fence is still
        // there as source: swap in the tree drawn as HTML instead.
        if (astFallback != null) {
            Element undrawn = bodyEl.selectFirst("h2.paperband-ast + pre:has(> code.language-plantuml)");
            if (undrawn != null) {
                for (Node n : new ArrayList<>(Jsoup.parseBodyFragment(astFallback).body().childNodes())) {
                    undrawn.before(n);
                }
                undrawn.remove();
            }
        }

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

        // 3c. Content policy: strip presentation smuggled in through raw HTML
        //     (inline style=, <style>/<script>, presentational tags/attrs) so
        //     the output stays theme-owned — see ContentSanitizer for the
        //     list and the reasoning. Classes and ids survive: they're the
        //     sanctioned styling route. Fenced/inline code is untouched by
        //     construction (its contents are escaped text, not elements).
        if (contentPolicy != ContentPolicy.ALLOW) {
            List<String> removedByPolicy = ContentSanitizer.strip(bodyEl);
            if (!removedByPolicy.isEmpty()) {
                if (contentPolicy == ContentPolicy.STRICT) {
                    throw new CardParseException(source + ": content policy 'strict' — "
                            + "presentation found in content: "
                            + String.join("; ", removedByPolicy)
                            + ". Move styling to classes ({.name} in markdown) plus the book "
                            + "css chain or theme, or declare vars: { contentPolicy: clean } "
                            + "to strip it, or allow to keep it.");
                }
                if (onRemoval != null) {
                    for (String r : removedByPolicy) onRemoval.accept(source + ": " + r);
                }
            }
        }

        String title = fm.getString("title").orElse(null);
        // An h1 fills the title slot only when the frontmatter hasn't. With a
        // `title:` declared, the card is already named and the author's first
        // h1 is just a heading -- consuming it would silently delete something
        // they wrote.
        boolean titleWanted = title == null || title.isBlank();
        if (explicitSections) {
            String[] titleOut = {title};
            List<Block> blocks = explicitBlocks(source, bodyEl, titleOut);
            return card(source, fm, titleOut[0], blocks);
        }
        List<Block> topLevel = new ArrayList<>();
        Deque<OpenSection> stack = new ArrayDeque<>();
        StringBuilder introHtml = new StringBuilder();
        boolean introFlushed = false;

        for (Node child : bodyEl.childNodes()) {
            if (child instanceof Element el) {
                String tag = el.tagName();
                int level = headingLevel(tag);
                // A card with no `title:` takes it from its first h1, which is
                // then consumed rather than rendered -- one heading, not a title
                // plus a duplicate of it. Every other h1 is structure the author
                // wrote (a long card numbering its steps with `#`) and opens a
                // section like any other heading.
                if (level == 1 && titleWanted) {
                    titleWanted = false;
                    title = el.text();
                    continue;
                }
                if (level >= 1) {
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
                    section.attributes = otherAttributes(source, el);
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

        return card(source, fm, title, topLevel);
    }

    private Card card(Path source, Frontmatter fm, String title, List<Block> blocks) {
        String id = fm.getString("id").orElseGet(() -> deriveIdFromFile(source));
        validateId(id, source);
        return new Card(id, source, fm, title, blocks);
    }

    /**
     * Blocks from a body whose structure {@link Sections} has made explicit:
     * each marked {@code <section>} is a block and its nested ones are its
     * children, so nothing is inferred here. Whatever sits outside every
     * section comes before the first heading -- Sections guarantees it -- and
     * is the intro. The title heading, when one was consumed, is marked and
     * taken out wherever it sits.
     *
     * @param titleOut in: the frontmatter title or null; out: the card's title
     */
    private List<Block> explicitBlocks(Path source, Element bodyEl, String[] titleOut) {
        List<Block> blocks = new ArrayList<>();
        StringBuilder intro = new StringBuilder();
        for (Node child : bodyEl.childNodes()) {
            if (child instanceof Element el && el.hasAttr(Sections.SECTION_ATTR)) {
                blocks.add(sectionBlock(source, el, titleOut));
            } else if (child instanceof Element el && el.hasAttr(Sections.TITLE_ATTR)) {
                titleOut[0] = el.text();
            } else if (child instanceof Element el) {
                intro.append(el.outerHtml());
            } else if (child instanceof TextNode tn && !tn.text().isBlank()) {
                intro.append(tn.text());
            }
        }
        if (!intro.isEmpty()) {
            blocks.add(0, new Block(Block.Kind.HEADING_SECTION, null, Set.of("intro"),
                    null, 0, intro.toString(), List.of()));
        }
        return blocks;
    }

    /** One marked {@code <section>}: its heading, its own content, its nested sections. */
    private Block sectionBlock(Path source, Element sectionEl, String[] titleOut) {
        OpenSection section = new OpenSection();
        section.level = Integer.parseInt(sectionEl.attr(Sections.SECTION_ATTR));
        for (org.jsoup.nodes.Attribute a : sectionEl.attributes()) {
            String key = a.getKey();
            if (key.startsWith(AttributeSyntax.DIRECTIVE_ATTR_PREFIX) && !key.equals(Sections.SECTION_ATTR)) {
                section.directives.put(key.substring(AttributeSyntax.DIRECTIVE_ATTR_PREFIX.length()),
                        a.getValue());
            }
        }
        boolean headingSeen = false;
        for (Node child : sectionEl.childNodes()) {
            if (child instanceof Element el) {
                if (!headingSeen && headingLevel(el.tagName()) >= 1) {
                    // Sections puts the heading first; everything after it is owned content.
                    headingSeen = true;
                    section.heading    = el.text();
                    section.id         = el.id().isEmpty() ? null : el.id();
                    section.classes    = parseClassAttr(el.className());
                    section.attributes = otherAttributes(source, el);
                } else if (el.hasAttr(Sections.SECTION_ATTR)) {
                    section.children.add(sectionBlock(source, el, titleOut));
                } else if (el.hasAttr(Sections.TITLE_ATTR)) {
                    titleOut[0] = el.text();
                } else {
                    section.html.append(el.outerHtml());
                }
            } else if (child instanceof TextNode tn && !tn.text().isBlank()) {
                section.html.append(tn.text());
            }
        }
        return build(section);
    }

    /** Apply {@link BlockTemplates} to every typed code block in {@code bodyEl}. */
    private void applyBlockTemplates(Path source, Element bodyEl) {
        for (Element code : new ArrayList<>(bodyEl.select("pre > code"))) {
            String type = null;
            List<String> extraClasses = new ArrayList<>();
            for (String token : code.className().split("\\s+")) {
                if (token.startsWith("language-")) {
                    if (type == null) type = token.substring("language-".length());
                } else if (!token.isBlank()) {
                    extraClasses.add(token);
                }
            }
            if (type == null || type.isBlank()) continue;
            Element pre = code.parent();
            // Info-line attributes land on the <pre> as well; carry them once.
            for (String token : pre.className().split("\\s+")) {
                if (!token.isBlank() && !token.startsWith("language-")
                        && !extraClasses.contains(token)) {
                    extraClasses.add(token);
                }
            }
            String id = !pre.id().isEmpty() ? pre.id()
                    : (code.id().isEmpty() ? null : code.id());
            String rendered;
            try {
                rendered = blockTemplates.render(type, code.wholeText(), extraClasses, id,
                        blockVars, source);
            } catch (BlockTemplates.BlockTemplateException e) {
                throw new CardParseException(source + ": ```" + type + " — " + e.getMessage(), e);
            }
            if (rendered == null) continue;      // not a block type: ordinary code
            Element frag = Jsoup.parseBodyFragment(rendered).body();
            for (Node n : new ArrayList<>(frag.childNodes())) {
                pre.before(n);
            }
            pre.remove();
        }
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
        Map<String, String> attributes;
        final Map<String, String> directives = new LinkedHashMap<>();
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
            // The heading as written, without "Step N": the class stays put
            // when renumbering changes the label.
            String slug = slug(Block.withoutStepLabel(section.heading, section.directives.get("step")));
            if (!slug.isEmpty()) finalClasses.add(slug);
        }
        return new Block(
                Block.Kind.HEADING_SECTION,
                section.id,
                finalClasses,
                section.heading,
                section.level,
                section.html.toString(),
                section.children,
                section.attributes,
                section.directives);
    }

    /**
     * A valid HTML attribute name. Markdown can't produce anything else --
     * {@link AttributeSyntax} rejects {@code {.step=1}} before rendering -- but
     * raw HTML and {@code .html} cards reach the heading walk without it, and
     * jsoup accepts {@code <h2 .step="1">}. That's not something a selector can
     * match, so it fails with the same suggestion rather than passing through.
     */
    private static final Pattern ATTRIBUTE_NAME = Pattern.compile("[A-Za-z_:][-A-Za-z0-9_:.]*");

    /**
     * The heading's attributes other than class and id. The heading element
     * itself is dissolved into a {@link Block}, so anything not copied out
     * here is lost -- which used to be every {@code {key=value}} an author put
     * on a heading, while the same attribute on a paragraph survived in its
     * html. The content policy has already run, so presentational attributes
     * are gone by this point; what's left is the author's own markup.
     */
    private static Map<String, String> otherAttributes(Path source, Element heading) {
        Map<String, String> out = new LinkedHashMap<>();
        for (org.jsoup.nodes.Attribute a : heading.attributes()) {
            String name = a.getKey();
            if (name.equals("class") || name.equals("id")) continue;
            if (!ATTRIBUTE_NAME.matcher(name).matches()) {
                // Failing beats dropping: a silently lost marker is exactly
                // the bug this method exists to fix.
                String bare = name.replaceFirst("^[.#]+", "");
                throw new CardParseException(source + ": heading '" + heading.text()
                        + "' has attribute '" + name + "', which isn't a valid HTML name. "
                        + "Write {" + bare + "=" + a.getValue() + "} for an attribute, or {."
                        + bare + " " + bare + "=" + a.getValue() + "} for a class as well.");
            }
            out.put(name, a.getValue());
        }
        return out;
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

    /**
     * The id for a card whose frontmatter declares none: its path relative to
     * the book root, slugified.
     *
     * <p>{@code scenarios/S01-spring-node/TRACE.md} &rarr;
     * {@code scenarios-s01-spring-node-trace}.
     *
     * <p>The filename alone isn't enough. A book that names every scenario's
     * file {@code TRACE.md} gives them all the id {@code TRACE}, and an id is
     * an identity everywhere downstream — the PDF's {@code #card-<id>}
     * destination, the site's {@code cards/<id>.html} page — so the duplicates
     * used to overwrite each other rather than coexist. The whole relative path
     * is unique by construction, which means no author has to hand-write ids to
     * get a correct book.
     *
     * <p>It's derived from that file's own path and nothing else, so it stays
     * put: adding, removing or renaming a <em>different</em> card can't change
     * this one's URL. That rules out the tempting alternative of qualifying
     * names only as far as the current collisions require.
     *
     * <p>With no book root (a card parsed on its own, outside a book) there's
     * nothing to be relative to, so the filename stands.
     */
    private String deriveIdFromFile(Path source) {
        Path relative = relativeToBookRoot(source);
        String withoutExtension = stripExtension(relative.toString());
        String slug = withoutExtension
                .replace('\\', '/')
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? stripExtension(source.getFileName().toString()) : slug;
    }

    /** The card's path relative to the book root, or its filename when that isn't possible. */
    private Path relativeToBookRoot(Path source) {
        if (bookRoot == null) return source.getFileName();
        try {
            Path relative = bookRoot.toAbsolutePath().normalize()
                    .relativize(source.toAbsolutePath().normalize());
            // A card outside the root would relativise to ../.. — meaningless
            // as an id, and a sign the caller passed the wrong root.
            return relative.startsWith("..") ? source.getFileName() : relative;
        } catch (IllegalArgumentException e) {
            return source.getFileName();
        }
    }

    private static String stripExtension(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        return dot > slash + 1 ? name.substring(0, dot) : name;
    }
}
