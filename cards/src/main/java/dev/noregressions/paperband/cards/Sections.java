package dev.noregressions.paperband.cards;

import org.commonmark.node.CustomBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.Node;
import org.commonmark.parser.PostProcessor;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlNodeRendererFactory;
import org.commonmark.renderer.html.HtmlWriter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes a markdown card's implicit structure explicit: every heading, and
 * everything it owns, becomes a {@link Section} node in the commonmark tree.
 *
 * <p>Markdown has no sections. A heading only <em>implies</em> one: it owns
 * whatever follows until the next heading at the same rank or higher, and a
 * deeper heading's section nests inside it -- the rank-based rule Pandoc's
 * {@code --section-divs} and Docutils' section transform use. Paperband used to
 * rediscover that structure after rendering, by walking the HTML's top-level
 * elements with jsoup. This post-processor writes it into the tree instead, so
 * every later step -- rendering, block building, and anything that wants one
 * section's content in another format -- starts from the same explicit
 * structure rather than each inferring it again.
 *
 * <h2>Raw HTML</h2>
 * <p>CommonMark keeps raw HTML as opaque text, so a raw element that opens in
 * one HTML block and closes in a later one isn't a node the tree can nest a
 * section in:
 * <pre>
 * &lt;div class="note"&gt;
 *
 * ## A heading inside
 *
 * &lt;/div&gt;
 * </pre>
 * That's the one shape raw HTML can't take here, and it fails the build rather
 * than splitting the {@code <div>} across two sections. Self-contained raw HTML
 * -- a whole {@code <table>}, a spliced fragment, an include snippet -- is
 * ordinary content of whichever section it falls in. A heading written in raw
 * HTML ({@code <h2>}) is content too, not a section: it's how an author gets a
 * heading that isn't one.
 *
 * <p>One instance serves one parse.
 */
final class Sections implements PostProcessor {

    /** A heading and everything it owns, nested by rank. Its first child is the heading. */
    static final class Section extends CustomBlock {
        private final int level;

        Section(int level) {
            this.level = level;
        }

        int level() {
            return level;
        }

        Heading heading() {
            return (Heading) getFirstChild();
        }
    }

    /**
     * Marks each rendered {@code <section>} so the block walk can tell
     * paperband's structure from a {@code <section>} an author typed. The value
     * is the heading's rank.
     */
    static final String SECTION_ATTR = "data-paperband-section";

    /** Marks the heading consumed as the card's title. */
    static final String TITLE_ATTR = "data-paperband-title";

    private final boolean titleWanted;
    private final AttributeSyntax syntax;
    private Heading title;

    /**
     * @param titleWanted true when the frontmatter names no title, so the first
     *                    top-level {@code h1} is the title rather than a section
     * @param syntax      where a heading's directives are kept; they render on
     *                    its section
     */
    Sections(boolean titleWanted, AttributeSyntax syntax) {
        this.titleWanted = titleWanted;
        this.syntax = syntax;
    }

    @Override
    public Node process(Node document) {
        Deque<Section> open = new ArrayDeque<>();
        RawHtml raw = new RawHtml();
        boolean wantTitle = titleWanted;
        Node node = document.getFirstChild();
        while (node != null) {
            Node next = node.getNext();
            if (node instanceof Heading heading) {
                raw.checkNotInside(heading);
                if (wantTitle && heading.getLevel() == 1) {
                    // Consumed as the title, not a section. It stays where it
                    // was written -- inside an open section if one is -- and
                    // is marked for the block walk to take out.
                    wantTitle = false;
                    title = heading;
                    if (!open.isEmpty()) open.peek().appendChild(heading);
                    node = next;
                    continue;
                }
                while (!open.isEmpty() && open.peek().level() >= heading.getLevel()) {
                    open.pop();
                }
                Section section = new Section(heading.getLevel());
                if (open.isEmpty()) heading.insertBefore(section);
                else open.peek().appendChild(section);
                section.appendChild(heading);        // unlinks it from where it was
                open.push(section);
            } else {
                if (node instanceof HtmlBlock html) raw.track(html.getLiteral());
                // Content before the first heading stays at the top level:
                // it's the card's intro.
                if (!open.isEmpty()) open.peek().appendChild(node);
            }
            node = next;
        }
        return document;
    }

    /** A heading's text, for messages. */
    static String text(Heading heading) {
        StringBuilder sb = new StringBuilder();
        heading.accept(new org.commonmark.node.AbstractVisitor() {
            @Override
            public void visit(org.commonmark.node.Text t) {
                sb.append(t.getLiteral());
            }

            @Override
            public void visit(org.commonmark.node.Code c) {
                sb.append(c.getLiteral());
            }
        });
        return sb.toString().strip();
    }

    /** Renders each {@link Section} as a marked {@code <section>}. */
    HtmlNodeRendererFactory renderer() {
        return context -> new SectionRenderer(context, syntax);
    }

    /** Marks the title heading, when there is one. */
    AttributeProvider titleMarker() {
        return (node, tagName, attributes) -> {
            if (node == title) attributes.put(TITLE_ATTR, "");
        };
    }

    private static final class SectionRenderer implements NodeRenderer {
        private final HtmlNodeRendererContext context;
        private final AttributeSyntax syntax;

        SectionRenderer(HtmlNodeRendererContext context, AttributeSyntax syntax) {
            this.context = context;
            this.syntax = syntax;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Section.class);
        }

        @Override
        public void render(Node node) {
            Section section = (Section) node;
            HtmlWriter html = context.getWriter();
            html.line();
            Map<String, String> attrs = new java.util.LinkedHashMap<>();
            attrs.put(SECTION_ATTR, String.valueOf(section.level()));
            // The heading's directives describe the section: {!step} numbers it
            // among its siblings, so the number rides here, not on the <hN>.
            syntax.directives(section.heading()).forEach((name, value) ->
                    attrs.put(AttributeSyntax.DIRECTIVE_ATTR_PREFIX + name, value));
            html.tag("section", attrs);
            html.line();
            for (Node child = section.getFirstChild(); child != null; child = child.getNext()) {
                context.render(child);
            }
            html.line();
            html.tag("/section");
            html.line();
        }
    }

    /**
     * Tracks raw HTML elements left open across top-level HTML blocks. Only
     * elements that need a closing tag count: {@code <p>}, {@code <li>},
     * {@code <td>} and friends close themselves, and void elements have nothing
     * to close.
     */
    private static final class RawHtml {
        private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z][A-Za-z0-9-]*)\\b[^<>]*?(/?)>");
        private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
        private static final Set<String> NO_CLOSE_NEEDED = Set.of(
                "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
                "source", "track", "wbr",
                "p", "li", "dt", "dd", "tr", "td", "th", "thead", "tbody", "tfoot",
                "option", "optgroup", "colgroup", "caption", "rp", "rt");

        private final Deque<String> open = new ArrayDeque<>();

        void track(String html) {
            Matcher m = TAG.matcher(COMMENT.matcher(html).replaceAll(""));
            while (m.find()) {
                String name = m.group(2).toLowerCase(java.util.Locale.ROOT);
                if (NO_CLOSE_NEEDED.contains(name) || !m.group(3).isEmpty()) continue;
                if (m.group(1).isEmpty()) {
                    open.push(name);
                } else if (open.contains(name)) {
                    // Close back to the matching element, the way a browser would.
                    while (!open.pop().equals(name)) { /* unwind */ }
                }
            }
        }

        void checkNotInside(Heading heading) {
            if (open.isEmpty()) return;
            throw new IllegalArgumentException("raw HTML <" + open.peekLast()
                    + "> is still open where heading '" + Sections.text(heading) + "' starts. A heading"
                    + " can't sit inside raw HTML, because it starts a section of its own: close"
                    + " the element before the heading, or put the heading's content inside the"
                    + " HTML instead.");
        }
    }
}
