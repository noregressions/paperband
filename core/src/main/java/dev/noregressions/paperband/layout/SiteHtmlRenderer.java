package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.HtmlIds;
import dev.noregressions.paperband.MarkdownFile;
import dev.noregressions.paperband.MarkdownHtmlRenderer;
import dev.noregressions.paperband.Site;
import dev.noregressions.paperband.Variants;
import org.commonmark.node.Node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes pages as one HTML document, in one of two modes:
 * <ul>
 *   <li>{@link Mode#WEB}: one flowing page. Page sizes, positions and size limits are ignored;
 *       boxes appear in layout order, in their grid areas (stacking on narrow screens), or in a
 *       stack.</li>
 *   <li>{@link Mode#PAGED}: fixed-size pages, for print and for measuring in a browser. Each page
 *       is a sheet of its page size in millimetres with a matching {@code @page} size and a page
 *       break after it. A layout with a grid, a stack or {@code at} boxes has a fixed shape: the
 *       sheet is exactly one page, {@code at} boxes are placed absolutely, grid rows not written
 *       out are sized by priority (rows holding an essential box fit their content; the others
 *       share what's left, never below their boxes' {@code size.min}), and {@code size} limits
 *       apply. Any other layout flows from page to page.</li>
 * </ul>
 * Structure, for styling:
 *
 * <pre>
 * &lt;section class="page layout-NAME [grid|stack] [layout class]" id="PAGE-ID" data-layout="NAME" data-path="docs/a.md"&gt;
 *   &lt;div class="box box-ID [box class]" data-box="PATH"&gt; ... &lt;/div&gt;   (unless wrap: false)
 *   &lt;div class="box box-ID" data-box="steps"&gt;            a box holding layouts: one per inner layout
 *     &lt;div class="layout layout-INNER [grid|stack]" data-layout="INNER"&gt;
 *       &lt;div class="box box-command" data-box="steps[2]/command"&gt; ... &lt;/div&gt;
 *     &lt;/div&gt;
 *   &lt;/div&gt;
 * &lt;/section&gt;
 * </pre>
 *
 * {@code data-box} is the box's path: its id, under its parent's path, with the match's index
 * (from 0) for an {@code each} box. Measurements and reports use it.
 * <p>
 * A fixed-text box writes its text; a source box writes the file's markdown as
 * {@code <pre class="source" data-stage="...">}, highlighted by {@link SourceHighlighter}.
 * Otherwise a box writes its nodes as HTML ({@code as: html}), their plain text ({@code text}), a
 * {@code <ul>} of their text ({@code list}), or a {@code <ul>} of links to the pages the nodes
 * came from ({@code links}). Stylesheets are inlined after a small base style, so the file
 * stands alone.
 */
public final class SiteHtmlRenderer {

    /** How pages are laid out: flowing for the web, or fixed-size for print. */
    public enum Mode { WEB, PAGED }

    /** A stylesheet to inline: its name (for a comment) and contents. */
    public record Stylesheet(String name, String css) {
    }

    private SiteHtmlRenderer() {
    }

    public static String render(String title, List<Stylesheet> stylesheets, List<Page> pages) {
        return render(title, stylesheets, pages, Mode.WEB);
    }

    public static String render(String title, List<Stylesheet> stylesheets, List<Page> pages, Mode mode) {
        return render(title, stylesheets, pages, mode, null);
    }

    /** @param fitCss what fit remedies set (see {@link dev.noregressions.paperband.fit.Fitter}), last so it wins; or null */
    public static String render(String title, List<Stylesheet> stylesheets, List<Page> pages, Mode mode, String fitCss) {
        return render(title, stylesheets, pages, mode, fitCss, List.of());
    }

    /**
     * @param copy fence languages whose code blocks get a Copy button, in web output only: a reader
     *             can paste a command without selecting it by hand. Print has no use for a button.
     */
    public static String render(String title, List<Stylesheet> stylesheets, List<Page> pages, Mode mode, String fitCss,
                                List<String> copy) {
        return render(title, stylesheets, pages, mode, fitCss, copy, Theme.DEFAULT);
    }

    /** @param theme the styles under everything else: its part for the mode, its content part, then its extra rules */
    public static String render(String title, List<Stylesheet> stylesheets, List<Page> pages, Mode mode, String fitCss,
                                List<String> copy, Theme theme) {
        Map<Path, String> anchors = new HashMap<>();
        for (Page p : pages) {
            if (p.source() != null) {
                anchors.putIfAbsent(p.source().normalize(), p.id());
            }
        }
        Map<String, Layout> layouts = usedLayouts(pages);

        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(esc(title)).append("</title>\n")
                .append("<style>\n").append(mode == Mode.WEB ? theme.css(Theme.Part.WEB)
                        : dev.noregressions.paperband.Fonts.css() + theme.css(Theme.Part.PAGED))
                .append(theme.css(Theme.Part.CONTENT)).append(theme.extra())
                .append(layoutCss(layouts, mode));
        if (mode == Mode.PAGED) {
            sb.append(pageRules(pages));
        }
        sb.append("</style>\n");
        for (Stylesheet s : stylesheets) {
            sb.append("<style>/* ").append(s.name().replace("*/", "")).append(" */\n").append(s.css()).append("\n</style>\n");
        }
        for (Layout layout : layouts.values()) {
            if (layout.css() != null) {
                sb.append("<style>/* layout ").append(layout.name()).append(" */\n").append(layout.css()).append("</style>\n");
            }
        }
        if (fitCss != null && !fitCss.isEmpty()) {
            sb.append("<style id=\"paperband-fit\">/* fit remedies */\n").append(fitCss).append("</style>\n");
        }
        sb.append("</head>\n<body class=\"").append(mode == Mode.WEB ? "web" : "paged").append("\">\n");
        Set<String> usedIds = new HashSet<>();
        pages.forEach(p -> usedIds.add(p.id()));
        for (Page page : pages) {
            Layout layout = page.layout();
            sb.append("<section class=\"page layout-").append(esc(layout.name())).append(shape(layout))
                    .append(page.grid() == null ? "" : " grid-" + esc(page.grid()))
                    .append(layout.cssClass() == null ? "" : " " + esc(layout.cssClass()))
                    .append("\" id=\"").append(esc(page.id())).append("\" data-layout=\"").append(esc(layout.name())).append('"');
            if (page.source() != null) {
                sb.append(" data-path=\"").append(esc(page.source().toString().replace('\\', '/'))).append('"');
            }
            if (mode == Mode.PAGED) {
                sb.append(" style=\"").append(pageStyle(page)).append('"');
            }
            sb.append(">\n");
            boxes(page.boxes(), "", mode, anchors, new HtmlIds(page.id(), usedIds, anchors), sb);
            sb.append("</section>\n");
        }
        if (mode == Mode.WEB && !copy.isEmpty()) {
            sb.append(copyButtons(copy));
        }
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private static void boxes(List<Page.PlacedBox> placedBoxes, String parentPath, Mode mode, Map<Path, String> anchors,
                              HtmlIds ids, StringBuilder sb) {
        for (Page.PlacedBox placed : placedBoxes) {
            Layout.Box box = placed.box();
            String path = parentPath.isEmpty() ? box.id() : parentPath + "/" + box.id();
            if (box.wrap()) {
                sb.append("<div class=\"box box-").append(esc(box.id()))
                        .append(box.cssClass() == null ? "" : " " + esc(box.cssClass()))
                        .append(box.kind() == Layout.Kind.VALUE && placed.source() != null ? " " + esc(placed.source()) : "")
                        .append("\" data-box=\"").append(esc(path)).append('"');
                String style = mode == Mode.PAGED ? boxStyle(box) : "";
                if (!style.isEmpty()) {
                    sb.append(" style=\"").append(style).append('"');
                }
                sb.append(">\n");
            }
            if (box.isText()) {
                sb.append(esc(box.text())).append('\n');
            } else if (box.isSource()) {
                sb.append("<pre class=\"source\" data-stage=\"").append(box.source().name().toLowerCase())
                        .append("\"><code>").append(SourceHighlighter.highlight(placed.source()))
                        .append("</code></pre>\n");
            } else if (box.kind() == Layout.Kind.LAYOUT_SOURCE) {
                sb.append("<pre class=\"source layout-source\"><code>").append(YamlHighlighter.highlight(placed.source()))
                        .append("</code></pre>\n");
            } else if (box.isFileInfo()) {
                sb.append(esc(placed.source())).append('\n');
            } else if (box.isNested()) {
                List<Page.Nested> nested = placed.nested();
                for (int i = 0; i < nested.size(); i++) {
                    Layout layout = nested.get(i).layout();
                    String innerPath = box.kind() == Layout.Kind.EACH ? path + "[" + i + "]" : path;
                    sb.append("<div class=\"layout layout-").append(esc(layout.name())).append(shape(layout))
                            .append(layout.cssClass() == null ? "" : " " + esc(layout.cssClass()))
                            .append("\" data-layout=\"").append(esc(layout.name())).append('"')
                            .append(placed.part() != null ? " data-unit=\"\"" : "").append(">\n");
                    boxes(nested.get(i).boxes(), innerPath, mode, anchors, ids, sb);
                    sb.append("</div>\n");
                }
            } else {
                List<Node> shown = placed.omit().isEmpty() ? placed.nodes()
                        : placed.nodes().stream().filter(n -> !placed.omit().contains(n)).toList();
                Page.Part part = placed.part();
                if (part != null && part.repeat() != null) {
                    sb.append(repeated(part, placed.prefer()));
                }
                int heading = box.kind() == Layout.Kind.VALUE ? box.value().heading() : 0;
                if (heading > 0) {
                    // A value shown as a heading: its phrase, inline, in <hN>; as plain text if it isn't one phrase.
                    sb.append("<h").append(heading).append('>');
                    if (box.as() == Layout.As.HTML && shown.size() == 1
                            && shown.getFirst() instanceof org.commonmark.node.Paragraph p) {
                        List<Node> inline = new ArrayList<>();
                        for (Node n = p.getFirstChild(); n != null; n = n.getNext()) {
                            inline.add(n);
                        }
                        sb.append(MarkdownHtmlRenderer.renderNodes(inline, ids, placed.prefer()).strip());
                    } else {
                        sb.append(esc(shown.stream().map(dev.noregressions.paperband.Site::text).collect(java.util.stream.Collectors.joining(" "))));
                    }
                    sb.append("</h").append(heading).append(">\n");
                } else if (box.kind() == Layout.Kind.VALUE && box.as() == Layout.As.HTML && shown.size() == 1
                        && shown.getFirst() instanceof org.commonmark.node.Paragraph p) {
                    // A one-paragraph value is a phrase: shown inline, without a <p> around it.
                    List<Node> inline = new ArrayList<>();
                    for (Node n = p.getFirstChild(); n != null; n = n.getNext()) {
                        inline.add(n);
                    }
                    sb.append(MarkdownHtmlRenderer.renderNodes(inline, ids, placed.prefer()).strip()).append('\n');
                } else if (box.as() == Layout.As.HTML) {
                    sb.append(MarkdownHtmlRenderer.renderNodes(shown, ids, placed.prefer(), placed.omit(),
                            part == null ? Set.of() : part.unitStarts()));
                } else {
                    content(box.as(), shown, placed.prefer(), anchors, ids, sb);
                }
            }
            if (box.wrap()) {
                sb.append("</div>\n");
            }
        }
    }

    /**
     * The node a continuation repeats from before the break, e.g. the heading of the section that
     * continues, with the split's label after it: {@code Steps (continued)}. Its ids are left
     * out, since the original has them.
     */
    private static String repeated(Page.Part part, dev.noregressions.paperband.Variants.Variant prefer) {
        String html = MarkdownHtmlRenderer.renderNodes(List.of(part.repeat()), new HtmlIds("repeat", new HashSet<>()), prefer);
        org.jsoup.nodes.Element body = org.jsoup.Jsoup.parseBodyFragment(html).body();
        body.select("[id]").removeAttr("id");
        org.jsoup.nodes.Element first = body.firstElementChild();
        if (first != null) {
            first.addClass("continued");
            if (part.label() != null) {
                first.appendElement("span").addClass("continued-label").text(" (" + part.label() + ")");
            }
        }
        return body.html() + "\n";
    }

    private static String shape(Layout layout) {
        return layout.grid() != null ? " grid" : layout.stack() != null ? " stack" : "";
    }

    /** A page's sheet: its size, its margin as padding, and its named {@code @page}. */
    private static String pageStyle(Page page) {
        PageSpec p = page.size();
        boolean fixed = page.layout().fixed();
        return "width: " + mm(p.width()) + "; " + (fixed ? "height: " : "min-height: ") + mm(p.height())
                + "; padding: " + mm(p.margin()) + "; page: " + (fixed ? pageName(p) : flowName(p)) + ";";
    }

    /** A box's place and size limits, for paged output. */
    private static String boxStyle(Layout.Box box) {
        List<String> parts = new ArrayList<>();
        if (box.at() != null) {
            Layout.Rect r = box.at();
            parts.add("position: absolute; left: " + mm(r.x()) + "; top: " + mm(r.y()) + "; width: " + mm(r.w())
                    + "; height: " + mm(r.h()) + ";");
        }
        Layout.Size size = box.rules().size();
        if (size != null) {
            if (size.min() != null) {
                parts.add("min-height: " + mm(size.min()) + ";");
            }
            if (size.max() != null) {
                parts.add("max-height: " + mm(size.max()) + ";");
            }
        }
        return String.join(" ", parts);
    }

    /** One {@code @page} rule per page size used. */
    /**
     * The {@code @page} rules. A page with a fixed shape is one sheet whose margin is its own
     * padding, so its {@code @page} has no margin. A flowing page can run onto several sheets,
     * so the margin is the {@code @page}'s, on every sheet, and in print the page drops its
     * padding and takes the width inside the margins. Margin boxes a stylesheet adds, such as
     * page numbers ({@code @page { @bottom-center { content: counter(page) } }}), so only show
     * on flowing pages.
     */
    private static String pageRules(List<Page> pages) {
        Map<String, PageSpec> fixed = new LinkedHashMap<>();
        Map<String, PageSpec> flowing = new LinkedHashMap<>();
        for (Page p : pages) {
            if (p.layout().fixed()) {
                fixed.putIfAbsent(pageName(p.size()), p.size());
            } else {
                flowing.putIfAbsent(flowName(p.size()), p.size());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (var e : fixed.entrySet()) {
            // Chromium draws margin boxes even with no margin: a stylesheet's page numbers would land on the page.
            sb.append("@page ").append(e.getKey()).append(" { size: ").append(mm(e.getValue().width())).append(' ')
                    .append(mm(e.getValue().height())).append("; margin: 0;");
            for (String box : MARGIN_BOXES) {
                sb.append(" @").append(box).append(" { content: none; }");
            }
            sb.append(" }\n");
        }
        for (var e : flowing.entrySet()) {
            PageSpec p = e.getValue();
            sb.append("@page ").append(e.getKey()).append(" { size: ").append(mm(p.width())).append(' ')
                    .append(mm(p.height())).append("; margin: ").append(mm(p.margin())).append("; }\n")
                    .append("@media print { section.page[style*=\"page: ").append(e.getKey()).append(";\"] { width: ")
                    .append(mm(p.width() - 2 * p.margin()))
                    // !important: the page's own style attribute sets its screen width and padding
                    .append(" !important; min-height: 0 !important; padding: 0 !important; } }\n");
        }
        return sb.toString();
    }

    private static final List<String> MARGIN_BOXES = List.of("top-left-corner", "top-left", "top-center", "top-right",
            "top-right-corner", "right-top", "right-middle", "right-bottom", "bottom-right-corner", "bottom-right",
            "bottom-center", "bottom-left", "bottom-left-corner", "left-bottom", "left-middle", "left-top");

    private static String pageName(PageSpec p) {
        return "p" + num(p.width()).replace('.', '_') + "x" + num(p.height()).replace('.', '_');
    }

    /** A flowing page's {@code @page} name: its size and margin, e.g. {@code f210x297m20}. */
    private static String flowName(PageSpec p) {
        return "f" + num(p.width()).replace('.', '_') + "x" + num(p.height()).replace('.', '_') + "m"
                + num(p.margin()).replace('.', '_');
    }

    /**
     * Per layout used, at any depth: its grid (rows sized by priority in paged output) or stack,
     * and each box's area. On the web, grids become one column on narrow screens. Boxes get
     * {@code min-width: 0} so wide code doesn't stretch a column.
     */
    private static String layoutCss(Map<String, Layout> layouts, Mode mode) {
        StringBuilder sb = new StringBuilder();
        for (Layout layout : layouts.values()) {
            String container = ".layout-" + layout.name();
            if (layout.grid() != null) {
                List<String> rows = mode == Mode.PAGED && !layout.grid().rowsGiven() ? priorityRows(layout, layout.grid())
                        : layout.grid().rows();
                sb.append(container).append(".grid { ").append(layout.grid().css(rows)).append(" }\n");
                for (var alt : layout.grids().entrySet()) { // the alternatives a fit remedy can switch a page to
                    Grid g = alt.getValue();
                    List<String> altRows = mode == Mode.PAGED && !g.rowsGiven() ? priorityRows(layout, g) : g.rows();
                    sb.append(container).append(".grid.grid-").append(alt.getKey()).append(" { ").append(g.css(altRows))
                            .append(" }\n");
                }
                for (Layout.Box box : layout.boxes()) {
                    sb.append(container).append(" > .box-").append(box.id()).append(" { grid-area: ").append(box.area())
                            .append("; min-width: 0; }\n");
                }
                if (mode == Mode.WEB) {
                    sb.append("@media (max-width: 700px) { ").append(container).append(".grid { display: block; } }\n");
                }
            }
            if (layout.stack() != null) {
                sb.append(container).append(".stack { display: flex; flex-direction: column;")
                        .append(layout.stack().gap() == null ? "" : " gap: " + layout.stack().gap() + ";").append(" }\n");
                if (mode == Mode.PAGED) {
                    for (Layout.Box box : layout.boxes()) {
                        boolean essential = box.rules().priority() == Layout.Priority.ESSENTIAL;
                        sb.append(container).append(" > .box-").append(box.id())
                                .append(essential ? " { flex: 0 0 auto; }\n" : " { flex: 1 1 0; min-height: 0; }\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Row sizes for a grid on a fixed page: a row holding an essential box fits its content
     * ({@code auto}); the others share what's left ({@code minmax(min, 1fr)}), never below the
     * largest {@code size.min} of their boxes.
     */
    static List<String> priorityRows(Layout layout, Grid grid) {
        Map<String, Layout.Box> byArea = new HashMap<>();
        for (Layout.Box box : layout.boxes()) {
            byArea.put(box.area(), box);
        }
        List<String> rows = new ArrayList<>();
        for (Set<String> areas : grid.rowAreas()) {
            boolean essential = areas.isEmpty();
            double min = 0;
            for (String area : areas) {
                Layout.Box box = byArea.get(area);
                if (box == null || box.rules().priority() == Layout.Priority.ESSENTIAL) {
                    essential = true;
                } else if (box.rules().size() != null && box.rules().size().min() != null) {
                    min = Math.max(min, box.rules().size().min());
                }
            }
            rows.add(essential ? "auto" : "minmax(" + mm(min) + ", 1fr)");
        }
        return rows;
    }

    /** Every layout the pages use, at any depth, by name, in first-use order. */
    private static Map<String, Layout> usedLayouts(List<Page> pages) {
        Map<String, Layout> used = new LinkedHashMap<>();
        for (Page p : pages) {
            used.putIfAbsent(p.layout().name(), p.layout());
            collect(p.boxes(), used);
        }
        return used;
    }

    private static void collect(List<Page.PlacedBox> boxes, Map<String, Layout> used) {
        for (Page.PlacedBox b : boxes) {
            for (Page.Nested n : b.nested()) {
                used.putIfAbsent(n.layout().name(), n.layout());
                collect(n.boxes(), used);
            }
        }
    }

    private static void content(Layout.As as, List<Node> nodes, Variants.Variant prefer, Map<Path, String> anchors,
                                HtmlIds ids, StringBuilder sb) {
        switch (as) {
            case HTML -> sb.append(MarkdownHtmlRenderer.renderNodes(nodes, ids, prefer));
            case TEXT -> sb.append(esc(String.join(" ", nodes.stream().map(n -> Site.text(n, prefer)).toList()))).append('\n');
            case LIST, LINKS -> {
                sb.append("<ul>\n");
                for (Node n : nodes) {
                    String text = esc(Site.text(n, prefer));
                    String anchor = as == Layout.As.LINKS ? anchorOf(n, anchors) : null;
                    sb.append("<li>").append(anchor == null ? text : "<a href=\"#" + esc(anchor) + "\">" + text + "</a>")
                            .append("</li>\n");
                }
                sb.append("</ul>\n");
            }
        }
    }

    /** The page id of the file a node came from, if that file has a page. */
    private static String anchorOf(Node node, Map<Path, String> anchors) {
        for (Node p = node; p != null; p = p.getParent()) {
            if (p instanceof MarkdownFile f) {
                return anchors.get(f.getPath().normalize());
            }
        }
        return null;
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String mm(double v) {
        return num(v) + "mm";
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", "");
    }

    /**
     * The Copy buttons: a script that adds one to each {@code <pre><code class="language-X">} for the
     * languages given, copying the block's text (without its final newline). It uses the clipboard
     * API where the page allows it, and a hidden text area where it doesn't (a page opened from disk).
     */
    /** {@link #copyButtons} for tests in other modules, which drive it in a real browser. */
    public static String copyButtonsForTest(List<String> languages) {
        return copyButtons(languages);
    }

    static String copyButtons(List<String> languages) {
        String list = languages.stream().map(l -> "\"" + l + "\"").collect(java.util.stream.Collectors.joining(","));
        return """
                <style>
                pre.has-copy { position: relative; padding-right: 4.5rem; }
                .copy-button { position: absolute; top: 6px; right: 6px; font: 12px/1 system-ui, sans-serif; padding: 4px 8px;
                  border: 1px solid rgba(128,128,128,.5); border-radius: 4px; background: rgba(255,255,255,.9); color: #1c1917;
                  cursor: pointer; opacity: .8; }
                .copy-button:hover, .copy-button:focus-visible { opacity: 1; }
                @media print { .copy-button { display: none; } }
                </style>
                <script>
                (function () {
                  var languages = [%s];
                  document.querySelectorAll("pre > code").forEach(function (code) {
                    if (!languages.some(function (l) { return code.classList.contains("language-" + l); })) return;
                    var pre = code.parentElement, button = document.createElement("button");
                    button.type = "button";
                    button.className = "copy-button";
                    button.textContent = "Copy";
                    button.setAttribute("aria-label", "Copy to clipboard");
                    button.addEventListener("click", function () {
                      var text = code.textContent.replace(/\\n$/, "");
                      var done = function () {
                        button.textContent = "Copied";
                        setTimeout(function () { button.textContent = "Copy"; }, 1500);
                      };
                      var fallback = function () {
                        var area = document.createElement("textarea");
                        area.value = text;
                        area.style.position = "fixed";
                        area.style.opacity = "0";
                        document.body.appendChild(area);
                        area.select();
                        try { document.execCommand("copy"); done(); } finally { area.remove(); }
                      };
                      if (navigator.clipboard && window.isSecureContext) {
                        navigator.clipboard.writeText(text).then(done, fallback);
                      } else {
                        fallback();
                      }
                    });
                    pre.classList.add("has-copy");
                    pre.appendChild(button);
                  });
                })();
                </script>
                """.formatted(list);
    }
}
