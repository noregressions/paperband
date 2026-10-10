package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.Attributes;
import dev.noregressions.paperband.Div;
import dev.noregressions.paperband.FrontMatterBlock;
import dev.noregressions.paperband.MarkdownFile;
import dev.noregressions.paperband.Site;
import dev.noregressions.paperband.Span;
import dev.noregressions.paperband.Variants;
import org.commonmark.node.*;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Evaluator;
import org.jsoup.select.Selector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An HTML-like element tree built from one file's AST, so layouts can pick content with CSS
 * selectors. Every element stands for AST nodes, so a selection maps straight back to the AST.
 *
 * <pre>
 * &lt;article&gt;                               the file
 *   &lt;section class="h1" id="intro"&gt;        a heading and everything up to the next heading
 *     &lt;h1&gt;Intro&lt;/h1&gt;                     of the same or a higher level (sections nest)
 *     &lt;p&gt;Text with &lt;em&gt;em&lt;/em&gt;, &lt;strong&gt;, &lt;code&gt;, &lt;a href&gt;, &lt;img src&gt;&lt;/p&gt;
 *     &lt;section class="h2" id="details"&gt;...&lt;/section&gt;
 *   &lt;/section&gt;
 * &lt;/article&gt;
 * </pre>
 *
 * Block elements: {@code h1}-{@code h6}, {@code p}, {@code ul}, {@code ol}, {@code li},
 * {@code blockquote}, {@code pre} (with {@code data-lang} and class {@code language-x} for fenced
 * code), {@code hr}, {@code div.html} (raw HTML blocks), and pipe tables as {@code table},
 * {@code thead}, {@code tbody}, {@code tr}, {@code th} and {@code td}. Section ids are the heading slugs used by
 * {@code site.section(...)}. Front matter isn't in the view.
 * <p>
 * {@code {! ...}} marker handles appear as on the HTML output: tags as classes, tagged values as
 * {@code data-*}, ids as ids. A heading's tags and values also go on its section, and its id is
 * the section's, so {@code .step} selects a whole step and {@code #download} a whole section.
 * {@code ::: } groups are {@code div} elements and {@code [text]{! tag}} spans are {@code span}s.
 * A {@code variants} group holds only its chosen variant (see {@link Variants}).
 * <p>
 * Selecting a section selects its heading and all its content. When both an element and
 * something inside it match, only the outer one counts, so nothing is placed twice.
 */
final class AstView {

    private final MarkdownFile file;
    private final Variants.Variant prefer;
    private final Element article;
    private final Map<Element, List<Node>> nodes = new IdentityHashMap<>();
    private final Map<Node, Element> elements = new IdentityHashMap<>();
    private final Map<Element, Integer> sectionLevels = new IdentityHashMap<>();

    AstView(MarkdownFile file) {
        this(file, null);
    }

    /** @param prefer the variant each {@link Variants} group shows (null: the longest present) */
    AstView(MarkdownFile file, Variants.Variant prefer) {
        this.prefer = prefer;
        Document doc = Document.createShell("");
        article = doc.body().appendElement("article");
        nodes.put(article, topLevel(file));

        this.file = file;
        Deque<Element> open = new ArrayDeque<>();
        for (Node block : topLevel(file)) {
            if (block instanceof Heading h) {
                while (!open.isEmpty() && sectionLevels.get(open.peek()) >= h.getLevel()) {
                    open.pop();
                }
                Element section = (open.isEmpty() ? article : open.peek()).appendElement("section")
                        .addClass("h" + h.getLevel())
                        .id(file.headingId(h));
                sectionLevels.put(section, h.getLevel());
                Attributes a = file.getAttributes(h);
                if (a != null) {
                    a.tags().forEach(section::addClass);
                    a.values().forEach((k, v) -> section.attr("data-" + k, v));
                }
                open.push(section);
            }
            build(block, open.isEmpty() ? article : open.peek());
        }
        mapSections(article);
    }

    /** The file's blocks, front matter left out. */
    static List<Node> topLevel(MarkdownFile file) {
        List<Node> out = new ArrayList<>();
        for (Node n = file.getFirstChild(); n != null; n = n.getNext()) {
            if (!(n instanceof FrontMatterBlock)) {
                out.add(n);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * The AST nodes selected from the whole file, in document order, without duplicates.
     *
     * @param pick null for every match, N (1-based) for the Nth, -1 for the last
     */
    List<Node> select(Evaluator selector, Integer pick) {
        return nodes(match(selector, pick, List.of(article)));
    }

    /** The element for the whole file. */
    Element root() {
        return article;
    }

    /**
     * The outermost elements matching {@code selector} within {@code roots} (a root itself can
     * match), in document order, then picked.
     *
     * @param pick null for every match, N (1-based) for the Nth, -1 for the last
     */
    List<Element> match(Evaluator selector, Integer pick, List<Element> roots) {
        List<Element> matched = new ArrayList<>();
        for (Element root : roots) {
            matched.addAll(Selector.select(selector, root));
        }
        Set<Element> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(matched);
        List<Element> outermost = new ArrayList<>();
        Set<Element> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Element e : matched) {
            if (nodes.containsKey(e) && seen.add(e) && e.parents().stream().noneMatch(set::contains)) {
                outermost.add(e);
            }
        }
        if (pick != null) {
            int i = pick == -1 ? outermost.size() - 1 : pick - 1;
            outermost = i >= 0 && i < outermost.size() ? List.of(outermost.get(i)) : List.of();
        }
        return outermost;
    }

    /** The AST nodes some elements stand for, in order, without duplicates. */
    List<Node> nodes(List<Element> elements) {
        Set<Node> out = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Node> ordered = new ArrayList<>();
        for (Element e : elements) {
            for (Node n : nodes.getOrDefault(e, List.of())) {
                if (out.add(n)) {
                    ordered.add(n);
                }
            }
        }
        return ordered;
    }

    /** The element standing for {@code node}, or null if it isn't in the view (an unchosen variant, say). */
    Element element(Node node) {
        return elements.get(node);
    }

    /** For tests and debugging: the element tree as HTML. */
    String outline() {
        return article.outerHtml();
    }

    private void build(Node node, Element parent) {
        Element e = switch (node) {
            case Heading h -> parent.appendElement("h" + h.getLevel());
            case Paragraph p -> parent.appendElement("p");
            case BulletList l -> parent.appendElement("ul");
            case OrderedList l -> parent.appendElement("ol");
            case ListItem i -> parent.appendElement("li");
            case BlockQuote q -> parent.appendElement("blockquote");
            case FencedCodeBlock f -> {
                Element pre = parent.appendElement("pre");
                String lang = f.getInfo() == null ? "" : f.getInfo().strip().split("\\s+")[0];
                if (!lang.isEmpty()) {
                    pre.attr("data-lang", lang).addClass("language-" + lang);
                }
                pre.appendText(f.getLiteral());
                yield pre;
            }
            case IndentedCodeBlock i -> parent.appendElement("pre").appendText(i.getLiteral());
            case HtmlBlock h -> parent.appendElement("div").addClass("html").appendText(h.getLiteral());
            case ThematicBreak t -> parent.appendElement("hr");
            case Emphasis em -> parent.appendElement("em");
            case StrongEmphasis s -> parent.appendElement("strong");
            case Code c -> parent.appendElement("code").appendText(c.getLiteral());
            case Link l -> parent.appendElement("a").attr("href", l.getDestination())
                    .attr("title", l.getTitle() == null ? "" : l.getTitle());
            case Image i -> parent.appendElement("img").attr("src", i.getDestination())
                    .attr("title", i.getTitle() == null ? "" : i.getTitle());
            case HtmlInline h -> parent.appendElement("span").addClass("html").appendText(h.getLiteral());
            case HardLineBreak b -> parent.appendElement("br");
            case Text t -> {
                parent.appendText(t.getLiteral());
                yield null;
            }
            case SoftLineBreak b -> {
                parent.appendText(" ");
                yield null;
            }
            case LinkReferenceDefinition d -> null;
            case Div d -> parent.appendElement("div");
            case org.commonmark.ext.gfm.tables.TableBlock t -> parent.appendElement("table");
            case org.commonmark.ext.gfm.tables.TableHead t -> parent.appendElement("thead");
            case org.commonmark.ext.gfm.tables.TableBody t -> parent.appendElement("tbody");
            case org.commonmark.ext.gfm.tables.TableRow t -> parent.appendElement("tr");
            case org.commonmark.ext.gfm.tables.TableCell c -> parent.appendElement(c.isHeader() ? "th" : "td");
            case Span s -> parent.appendElement("span");
            case Block b -> parent.appendElement("div").addClass(b.getClass().getSimpleName());
            default -> parent.appendElement("span").addClass(node.getClass().getSimpleName());
        };
        if (e == null) {
            return;
        }
        nodes.put(e, List.of(node));
        elements.put(node, e);
        Attributes a = file.getAttributes(node);
        if (a != null) {
            a.tags().forEach(e::addClass);
            a.values().forEach((k, v) -> e.attr("data-" + k, v));
            if (a.id() != null && !(node instanceof Heading)) {
                e.id(a.id());
            }
        }
        if (Variants.isGroup(node)) {
            Node chosen = Variants.choose(node, prefer); // the other variants aren't in the view at all
            if (chosen != null) {
                build(chosen, e);
            }
        } else if (!(node instanceof Image)) {
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                build(child, e);
            }
        } else {
            e.attr("alt", Site.text(node));
        }
    }

    /** A section stands for its heading plus everything nested in it, in order. */
    private List<Node> mapSections(Element parent) {
        List<Node> all = new ArrayList<>();
        for (Element child : parent.children()) {
            if (child.tagName().equals("section")) {
                List<Node> inner = mapSections(child);
                nodes.put(child, List.copyOf(inner));
                all.addAll(inner);
            } else if (nodes.containsKey(child)) {
                all.addAll(nodes.get(child));
            }
        }
        return all;
    }

    /** Nodes (by identity) covered by a list of selections, for working out what's left. */
    static Set<Node> identitySet(List<Node> nodes) {
        Set<Node> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(nodes);
        return set;
    }
}
