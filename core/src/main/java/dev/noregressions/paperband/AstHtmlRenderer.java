package dev.noregressions.paperband;

import org.commonmark.node.Node;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link CombinedAst} as a self-contained HTML page: a front matter
 * summary per file, a collapsible, colour-coded tree of every node, and the
 * markdown rendered to HTML from that same AST (see {@link MarkdownHtmlRenderer}).
 * <p>
 * The rendered markdown sits in a sandboxed {@code <iframe srcdoc>}, so raw HTML in
 * it can't break this page's layout or styles, and scripts in it don't run.
 */
public final class AstHtmlRenderer {

    private AstHtmlRenderer() {
    }

    public static final String DEFAULT_TITLE = "Combined Markdown AST";
    public static final String DRAFT_TITLE = "Draft Markdown AST (after the pre pass)";

    public static String render(CombinedAst ast) {
        return render(ast, DEFAULT_TITLE);
    }

    /** @param title the page heading, e.g. to tell a draft AST from the final one */
    public static String render(CombinedAst ast, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                """);
        sb.append("<title>").append(esc(title)).append("</title>\n<style>\n").append(CSS).append("""
                </style>
                </head>
                <body>
                <header>
                """);
        sb.append("  <h1>").append(esc(title)).append("</h1>\n");
        sb.append("  <p class=\"meta\">").append(ast.getFiles().size()).append(" file(s), ")
                .append(countNodes(ast.getRoot())).append(" nodes</p>\n");
        sb.append("""
                  <div class="controls">
                    <button onclick="toggleAll(true)">Expand all</button>
                    <button onclick="toggleAll(false)">Collapse all</button>
                    <a class="jump" href="#rendered">Rendered markdown ↓</a>
                  </div>
                  <ul class="legend">
                    <li><span class="node c-root">Document</span></li>
                    <li><span class="node c-file">File</span></li>
                    <li><span class="node c-front_matter">Front matter</span></li>
                    <li><span class="node c-block">Block</span></li>
                    <li><span class="node c-inline">Inline</span></li>
                    <li><span class="node c-text">Text</span></li>
                  </ul>
                </header>
                <main>
                <section>
                <h2>Front matter</h2>
                """);
        renderFrontMatter(ast.getFrontMatter(), sb);
        sb.append("</section>\n<section>\n<h2>Tree</h2>\n<ul class=\"tree\">\n");
        renderNode(ast.getRoot(), sb);
        sb.append("""
                </ul>
                </section>
                <section id="rendered">
                <h2>Rendered markdown</h2>
                <p class="meta">HTML rendered from the AST above. Front matter isn't rendered.</p>
                """);
        sb.append("<iframe class=\"rendered\" title=\"Rendered markdown\" ")
                .append("sandbox=\"allow-same-origin allow-popups allow-popups-to-escape-sandbox\" srcdoc=\"")
                .append(esc(renderedDocument(ast))).append("\"></iframe>\n");
        sb.append("""
                </section>
                </main>
                <script>
                function toggleAll(open) {
                  document.querySelectorAll('.tree details').forEach(d => d.open = open);
                }
                // Size each rendered-markdown frame to its content, and keep it sized as images load.
                document.querySelectorAll('iframe.rendered').forEach(frame => {
                  const fit = () => {
                    const doc = frame.contentDocument;
                    if (doc && doc.documentElement) frame.style.height = doc.documentElement.scrollHeight + 'px';
                  };
                  frame.addEventListener('load', () => {
                    fit();
                    new ResizeObserver(fit).observe(frame.contentDocument.body);
                  });
                  fit();
                });
                </script>
                </body>
                </html>
                """);
        return sb.toString();
    }

    /** The standalone document shown in the rendered-markdown frame. */
    private static String renderedDocument(CombinedAst ast) {
        return "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<base target=\"_blank\">\n<style>\n" + RENDERED_CSS + "</style>\n</head>\n<body>\n"
                + MarkdownHtmlRenderer.render(ast) + "</body>\n</html>\n";
    }

    private static void renderFrontMatter(Map<Path, Map<String, Object>> frontMatter, StringBuilder sb) {
        sb.append("<div class=\"fm-grid\">\n");
        for (var entry : frontMatter.entrySet()) {
            sb.append("<div class=\"fm-card\">\n<h3>").append(esc(entry.getKey().toString())).append("</h3>\n");
            if (entry.getValue().isEmpty()) {
                sb.append("<p class=\"none\">No front matter</p>\n");
            } else {
                sb.append("<table>\n");
                for (var kv : entry.getValue().entrySet()) {
                    sb.append("<tr><th>").append(esc(kv.getKey())).append("</th><td>")
                            .append(esc(FrontMatter.format(kv.getValue()))).append("</td></tr>\n");
                }
                sb.append("</table>\n");
            }
            sb.append("</div>\n");
        }
        sb.append("</div>\n");
    }

    private static void renderNode(Node node, StringBuilder sb) {
        sb.append("<li>");
        if (node.getFirstChild() == null) {
            sb.append("<div class=\"leaf\">");
            renderLabel(node, sb);
            sb.append("</div>");
        } else {
            sb.append("<details open><summary>");
            renderLabel(node, sb);
            sb.append("</summary>\n<ul>\n");
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                renderNode(child, sb);
            }
            sb.append("</ul>\n</details>");
        }
        sb.append("</li>\n");
    }

    private static void renderLabel(Node node, StringBuilder sb) {
        String category = NodeInfo.category(node).name().toLowerCase();
        sb.append("<span class=\"node c-").append(category).append("\">")
                .append(esc(NodeInfo.name(node))).append("</span>");
        String detail = NodeInfo.detail(node);
        if (!detail.isEmpty()) {
            sb.append(" <span class=\"detail\">").append(esc(detail)).append("</span>");
        }
        int line = NodeInfo.line(node);
        if (line > 0) {
            sb.append(" <span class=\"line\">L").append(line).append("</span>");
        }
    }

    private static int countNodes(Node node) {
        int n = 1;
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            n += countNodes(child);
        }
        return n;
    }

    private static String esc(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static final String RENDERED_CSS = """
            :root { color-scheme: light dark; --fg: #1c1917; --muted: #78716c; --line: #d6d3d1; --code: #f5f5f4;
                    --link: #1d4ed8; --bg: #ffffff; }
            @media (prefers-color-scheme: dark) {
              :root { --fg: #f5f5f4; --muted: #a8a29e; --line: #44403c; --code: #1c1917; --link: #60a5fa; --bg: #292524; }
            }
            html { background: var(--bg); }
            body { margin: 0; padding: 20px; color: var(--fg); font: 15px/1.6 system-ui, -apple-system, sans-serif;
                   overflow-wrap: anywhere; }
            a { color: var(--link); }
            .md-file + .md-file { margin-top: 32px; padding-top: 24px; border-top: 1px solid var(--line); }
            .md-file-path { font: 12px ui-monospace, monospace; color: var(--muted); margin-bottom: 8px; }
            h1, h2, h3 { line-height: 1.25; margin: 1.2em 0 0.5em; }
            .md-file-path + * { margin-top: 0; }
            code { font: 0.9em ui-monospace, monospace; background: var(--code); padding: 1px 4px; border-radius: 4px; }
            pre { background: var(--code); padding: 12px; border-radius: 6px; overflow-x: auto; }
            pre code { padding: 0; background: none; }
            blockquote { margin: 0; padding-left: 12px; border-left: 3px solid var(--line); color: var(--muted); }
            hr { border: 0; border-top: 1px solid var(--line); }
            img { max-width: 100%; }
            """;

    private static final String CSS = """
            :root {
              --bg: #fafaf9; --fg: #1c1917; --muted: #78716c; --line: #d6d3d1; --card: #ffffff;
              --root: #44403c; --file: #1d4ed8; --fm: #b45309; --block: #047857; --inline: #7c3aed; --text: #be185d;
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #1c1917; --fg: #f5f5f4; --muted: #a8a29e; --line: #44403c; --card: #292524;
                --root: #a8a29e; --file: #60a5fa; --fm: #fbbf24; --block: #34d399; --inline: #a78bfa; --text: #f472b6;
              }
            }
            * { box-sizing: border-box; }
            body { margin: 0; padding: 24px 16px; background: var(--bg); color: var(--fg);
                   font: 14px/1.5 system-ui, -apple-system, sans-serif; }
            header, main { max-width: 1100px; margin: 0 auto; }
            h1 { margin: 0 0 4px; font-size: 22px; }
            h2 { font-size: 16px; margin: 28px 0 12px; }
            h3 { font-size: 14px; margin: 0 0 8px; font-family: ui-monospace, monospace; color: var(--file); }
            .meta { margin: 0 0 12px; color: var(--muted); }
            .controls button { font: inherit; padding: 4px 10px; margin-right: 6px; border: 1px solid var(--line);
                               background: var(--card); color: var(--fg); border-radius: 6px; cursor: pointer; }
            .legend { list-style: none; padding: 0; margin: 12px 0 0; display: flex; flex-wrap: wrap; gap: 8px; }
            .fm-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
            .fm-card { background: var(--card); border: 1px solid var(--line); border-radius: 8px; padding: 12px; overflow-x: auto; }
            .fm-card table { border-collapse: collapse; width: 100%; }
            .fm-card th, .fm-card td { text-align: left; vertical-align: top; padding: 3px 8px 3px 0;
                                       border-top: 1px solid var(--line); font-family: ui-monospace, monospace; font-size: 12px; }
            .fm-card th { color: var(--fm); white-space: nowrap; }
            .none { color: var(--muted); margin: 0; }
            .tree, .tree ul { list-style: none; margin: 0; padding: 0; }
            .tree ul { margin-left: 10px; padding-left: 18px; border-left: 1px solid var(--line); }
            .tree li { position: relative; margin: 3px 0; }
            .tree ul > li::before { content: ""; position: absolute; left: -18px; top: 12px; width: 14px;
                                    border-top: 1px solid var(--line); }
            .tree summary { cursor: pointer; }
            .tree summary, .tree .leaf { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
            main section:last-child { overflow-x: auto; }
            .node { display: inline-block; padding: 1px 8px; border-radius: 999px; font-size: 12px; font-weight: 600;
                    border: 1px solid currentColor; background: var(--card); }
            .c-root { color: var(--root); } .c-file { color: var(--file); } .c-front_matter { color: var(--fm); }
            .c-block { color: var(--block); } .c-inline { color: var(--inline); } .c-text { color: var(--text); }
            .detail { font-family: ui-monospace, monospace; font-size: 12px; }
            .jump { margin-left: 6px; color: var(--file); }
            iframe.rendered { display: block; width: 100%; min-height: 120px; border: 1px solid var(--line);
                              border-radius: 8px; background: var(--card); }
            .line { color: var(--muted); font-size: 11px; }
            """;
}
