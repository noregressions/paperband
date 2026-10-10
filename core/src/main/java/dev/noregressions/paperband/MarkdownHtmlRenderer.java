package dev.noregressions.paperband;

import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders a {@link CombinedAst} to HTML with commonmark's renderer: each file becomes an
 * {@code <article class="md-file" data-path="...">}, and front matter is left out.
 * Raw HTML in the markdown is passed through as written.
 */
public final class MarkdownHtmlRenderer {

    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(CombinedAst.EXTENSIONS)
            .nodeRendererFactory(context -> new CustomNodeRenderer(context, null))
            .attributeProviderFactory(context -> MarkdownHtmlRenderer::markerAttributes)
            .build();

    /** Adds marker handles to an element: id, class (tags) and data-* (tagged values). */
    private static void markerAttributes(Node node, String tagName, Map<String, String> attributes) {
        Attributes a = MarkdownFile.attributesOf(node);
        if (a == null || ((node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) && !tagName.equals("pre"))) {
            return;
        }
        a.html().forEach((k, v) -> attributes.merge(k, v, (old, add) -> k.equals("class") ? old + " " + add : add));
    }

    private MarkdownHtmlRenderer() {
    }

    public static String render(CombinedAst ast) {
        return RENDERER.render(ast.getRoot());
    }

    /** Renders AST nodes one after another, e.g. the nodes a layout box selected. */
    public static String renderNodes(List<Node> nodes) {
        return renderNodes(nodes, RENDERER);
    }

    /**
     * Renders AST nodes for a page of a site: ids go through {@code ids} (see {@link HtmlIds}),
     * top-level headings get their section ids as anchors, and in-page links ({@code #id}) to
     * anchors in the same file are rewritten to match.
     */
    public static String renderNodes(List<Node> nodes, HtmlIds ids) {
        return renderNodes(nodes, ids, null);
    }

    /** As {@link #renderNodes(List, HtmlIds)}, showing each variants group's variant for {@code prefer}. */
    public static String renderNodes(List<Node> nodes, HtmlIds ids, Variants.Variant prefer) {
        return renderNodes(nodes, ids, prefer, Set.of());
    }

    /** Marks the elements of nodes to leave out, so they can be taken out after rendering. */
    private static final String OMIT = "data-paperband-omit";

    /**
     * As {@link #renderNodes(List, HtmlIds, Variants.Variant)}, leaving out {@code omit} (by
     * identity): blocks a fit remedy dropped. The AST itself is never changed, since other
     * outputs render the same nodes.
     */
    public static String renderNodes(List<Node> nodes, HtmlIds ids, Variants.Variant prefer, Set<Node> omit) {
        return renderNodes(nodes, ids, prefer, omit, Set.of());
    }

    /** Marks where each unit of a box that splits across pages starts, for the browser to measure. */
    public static final String UNIT = "data-unit";

    /** As {@link #renderNodes(List, HtmlIds, Variants.Variant, Set)}, with {@code units} (by identity) marked {@value #UNIT}. */
    public static String renderNodes(List<Node> nodes, HtmlIds ids, Variants.Variant prefer, Set<Node> omit,
                                     Set<Node> units) {
        List<Node> shown = omit.isEmpty() ? nodes : nodes.stream().filter(n -> !omit.contains(n)).toList();
        String html = renderNodes(shown, HtmlRenderer.builder()
                .extensions(CombinedAst.EXTENSIONS)
                .nodeRendererFactory(context -> new CustomNodeRenderer(context, prefer))
                .nodeRendererFactory(context -> new PageLinkRenderer(context, ids))
                .nodeRendererFactory(context -> new FencedBlockRenderer(context))
                .attributeProviderFactory(context -> (node, tagName, attributes) -> {
                    markerAttributes(node, tagName, attributes);
                    siteIds(node, attributes, ids);
                    if (omit.contains(node)) {
                        attributes.put(OMIT, "");
                    }
                    if (units.contains(node)) {
                        attributes.put(UNIT, "");
                    }
                })
                .build());
        if (!html.contains(OMIT)) {
            return html;
        }
        org.jsoup.nodes.Element body = org.jsoup.Jsoup.parseBodyFragment(html).body();
        body.select("[" + OMIT + "]").remove();
        return body.html() + "\n";
    }

    private static String renderNodes(List<Node> nodes, HtmlRenderer renderer) {
        StringBuilder sb = new StringBuilder();
        for (Node n : nodes) {
            sb.append(renderer.render(n));
        }
        return sb.toString();
    }

    private static void siteIds(Node node, Map<String, String> attributes, HtmlIds ids) {
        MarkdownFile file = fileOf(node);
        if (file == null) {
            return;
        }
        if (node instanceof Heading h && h.getParent() == file) {
            attributes.put("id", ids.element(file.headingId(h)));
        } else if (attributes.containsKey("id")) {
            Attributes a = file.getAttributes(node);
            if (a != null && a.id() != null) {
                attributes.put("id", ids.element(a.id()));
            }
        }
        if (node instanceof Link link && link.getDestination().startsWith("#")
                && file.anchors().contains(link.getDestination().substring(1))) {
            attributes.put("href", ids.link(link.getDestination().substring(1)));
        }
    }

    private static MarkdownFile fileOf(Node node) {
        for (Node p = node; p != null; p = p.getParent()) {
            if (p instanceof MarkdownFile f) {
                return f;
            }
        }
        return null;
    }

    /** The block renderers on the classpath, found once. */
    private static final List<dev.noregressions.paperband.blocks.BlockRenderer> BLOCK_RENDERERS = dev.noregressions.paperband.blocks.BlockRenderer.all();

    /**
     * Fenced code blocks in a language a {@link dev.noregressions.paperband.blocks.BlockRenderer} claims, as that renderer's
     * HTML inside a {@code <div class="block block-LANGUAGE">}; other fences as commonmark renders them.
     */
    private static final class FencedBlockRenderer implements NodeRenderer {

        private final HtmlNodeRendererContext context;
        private final HtmlWriter html;

        FencedBlockRenderer(HtmlNodeRendererContext context) {
            this.context = context;
            this.html = context.getWriter();
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return BLOCK_RENDERERS.isEmpty() ? Set.of() : Set.of(FencedCodeBlock.class);
        }

        @Override
        public void render(Node node) {
            FencedCodeBlock code = (FencedCodeBlock) node;
            String info = code.getInfo() == null ? "" : code.getInfo().strip();
            String language = info.isEmpty() ? "" : info.split("\\s+")[0];
            dev.noregressions.paperband.blocks.BlockRenderer renderer = dev.noregressions.paperband.blocks.BlockRenderer.forLanguage(BLOCK_RENDERERS, language);
            html.line();
            if (renderer != null) {
                String out;
                try {
                    out = renderer.render(language, code.getLiteral());
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException("```" + language + " block (line " + NodeInfo.line(code)
                            + "): " + e.getMessage(), e);
                }
                html.tag("div", context.extendAttributes(code, "div", Map.of("class", "block block-" + language)));
                html.raw(out);
                html.tag("/div");
            } else { // as commonmark's own renderer does
                Map<String, String> attrs = language.isEmpty() ? Map.of() : Map.of("class", "language-" + language);
                html.tag("pre", context.extendAttributes(code, "pre", Map.of()));
                html.tag("code", context.extendAttributes(code, "code", attrs));
                html.text(code.getLiteral());
                html.tag("/code");
                html.tag("/pre");
            }
            html.line();
        }
    }

    /**
     * Links to other pages by their front matter id ({@link PageLinks}): to the page's id in this
     * document, with its number or title as the text where the link asks for it; just the text
     * where the document leaves the page out. Other links render as usual.
     */
    private static final class PageLinkRenderer implements NodeRenderer {

        private final HtmlNodeRendererContext context;
        private final HtmlWriter html;
        private final HtmlIds ids;

        PageLinkRenderer(HtmlNodeRendererContext context, HtmlIds ids) {
            this.context = context;
            this.html = context.getWriter();
            this.ids = ids;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Link.class);
        }

        @Override
        public void render(Node node) {
            Link link = (Link) node;
            MarkdownFile target = PageLinks.target(link);
            String label = target == null ? null : PageLinks.label(link, target);
            String page = target == null ? null : ids.page(target.getPath());
            if (target != null && page == null) {
                if (label != null) {
                    html.text(label);
                } else {
                    children(link);
                }
                return;
            }
            Map<String, String> attrs = new java.util.LinkedHashMap<>();
            attrs.put("href", page != null ? "#" + page : context.encodeUrl(link.getDestination()));
            if (link.getTitle() != null) {
                attrs.put("title", link.getTitle());
            }
            html.tag("a", context.extendAttributes(link, "a", attrs));
            if (label != null) {
                html.text(label);
            } else {
                children(link);
            }
            html.tag("/a");
        }

        private void children(Node parent) {
            for (Node c = parent.getFirstChild(); c != null; c = c.getNext()) {
                context.render(c);
            }
        }
    }

    /** Handles the node types this project adds; commonmark would otherwise skip them and their children. */
    private static final class CustomNodeRenderer implements NodeRenderer {

        private final HtmlNodeRendererContext context;
        private final HtmlWriter html;
        private final Variants.Variant prefer;

        CustomNodeRenderer(HtmlNodeRendererContext context, Variants.Variant prefer) {
            this.context = context;
            this.html = context.getWriter();
            this.prefer = prefer;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(MarkdownFile.class, FrontMatterBlock.class, Div.class, Span.class);
        }

        @Override
        public void render(Node node) {
            if (node instanceof MarkdownFile file) {
                String path = file.getPath().toString();
                html.line();
                html.tag("article", Map.of("class", "md-file", "data-path", path));
                html.line();
                html.tag("header", Map.of("class", "md-file-path"));
                html.text(path);
                html.tag("/header");
                html.line();
                for (Node child = file.getFirstChild(); child != null; child = child.getNext()) {
                    context.render(child);
                }
                html.tag("/article");
                html.line();
            }
            if (node instanceof Div || node instanceof Span) {
                String tag = node instanceof Div ? "div" : "span";
                if (node instanceof Div) {
                    html.line();
                }
                html.tag(tag, context.extendAttributes(node, tag, Map.of()));
                if (Variants.isGroup(node)) {
                    Node chosen = Variants.choose(node, prefer); // only the chosen variant is shown
                    if (chosen != null) {
                        context.render(chosen);
                    }
                } else {
                    for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                        context.render(child);
                    }
                }
                html.tag("/" + tag);
                if (node instanceof Div) {
                    html.line();
                }
            }
            // FrontMatterBlock: metadata, not content, so nothing is rendered.
        }
    }
}
