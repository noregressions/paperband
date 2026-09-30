package dev.noregressions.paperband.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One piece of a block's content, as data: a paragraph, a fence, a list, a
 * table cell, a link, a run of text. A {@link Block} carries the nodes of its
 * own direct content, in document order, alongside the same content as
 * {@link Block#html()}, so a layout template can ask for "this step's command"
 * without cutting it out of a string.
 *
 * <p>Nodes are read from the card's finished HTML -- after block templates,
 * the content policy and inline-code classification -- so they describe the
 * content a template would otherwise print, not an earlier draft of it. Where
 * a pass rewrote something whose origin matters, the node keeps the origin: a
 * {@code ```command} fence that its block template turned into
 * {@code <pre class="command">} is still type {@code fence} with
 * {@code lang=command} and its source text as {@code code}.
 *
 * <p>The type names the markdown construct, not the tag:
 * <ul>
 *   <li>block: {@code paragraph}, {@code fence}, {@code list}, {@code item},
 *       {@code table}, {@code row}, {@code cell}, {@code quote},
 *       {@code figure}, {@code rule}</li>
 *   <li>inline: {@code text}, {@code code}, {@code emphasis}, {@code strong},
 *       {@code link}, {@code image}, {@code span}, {@code break}</li>
 *   <li>{@code element}: anything else -- raw HTML, a diagram, a table's
 *       {@code <thead>}. Its {@code html} is the content; its children are
 *       read like any other, except inside {@code svg} and {@code math},
 *       whose internals are drawing, not text.</li>
 * </ul>
 *
 * @param type       what the node is; see above
 * @param tag        the element's tag, or null for a {@code text} node
 * @param id         the element's id, or null
 * @param classes    the element's classes, in order; never null
 * @param attributes every other attribute, in order, except class, id and
 *                   {@code data-paperband-*}; never null
 * @param directives paperband directives on the element ({@code !step} on a
 *                   fence), by name without the {@code !}; never null
 * @param text       the node's text content, whitespace-normalised; for a
 *                   fence, see {@code props.code} for the text as written
 * @param html       the node's outer HTML, or for a {@code text} node its
 *                   escaped text
 * @param children   the node's children; never null, empty for text
 * @param props      what only some types have: {@code lang} and {@code code}
 *                   for a fence, {@code ordered} for a list, {@code header}
 *                   for a cell; never null
 */
public record Node(
        String type,
        String tag,
        String id,
        Set<String> classes,
        Map<String, String> attributes,
        Map<String, String> directives,
        String text,
        String html,
        List<Node> children,
        Map<String, String> props
) {

    public Node {
        classes = classes == null || classes.isEmpty() ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(classes));
        attributes = ordered(attributes);
        directives = ordered(directives);
        children = children == null ? List.of() : List.copyOf(children);
        props = ordered(props);
    }

    /** A run of text: no tag, no attributes, no children. */
    public static Node text(String text, String html) {
        return new Node("text", null, null, Set.of(), Map.of(), Map.of(), text, html, List.of(), Map.of());
    }

    // Map.copyOf would lose source order -- see Block.
    private static Map<String, String> ordered(Map<String, String> m) {
        return m == null || m.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }
}
