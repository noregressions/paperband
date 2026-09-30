package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.cards.BlockTemplates;
import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes a block's content for one output: its nodes, with each fence that has
 * a {@code blocks/<type>.html} template written through it.
 *
 * <p>A block template is layout -- it decides what a {@code ```command} looks
 * like -- so it runs here, when an output is written, and not when a card is
 * read. A card's loading leaves such a fence as a plain code block; what a
 * renderer module draws it has already drawn, once, and that is written as it
 * is. The template chain is the one {@link BlockTemplates} resolves: the
 * theme's, the book's {@code layouts/blocks/}, then the bundled set.
 */
public final class ContentWriter {

    private final BlockTemplates templates;

    /**
     * @param templates the book's block templates; null for the bundled set alone
     */
    public ContentWriter(BlockTemplates templates) {
        this.templates = templates == null ? BlockTemplates.bundled() : templates;
    }

    /**
     * A block's own content as HTML, its children excluded like
     * {@link Block#html()}'s.
     *
     * @param block  the block
     * @param vars   the card's vars, which a block template sees as {@code vars}
     * @param source the card's file, for error messages; may be null
     * @param target the output being written, which a template sees as
     *               {@code target}; may be null
     * @return the content, with templated fences written through their templates
     * @throws LayoutException when a block template fails
     */
    public String html(Block block, Map<String, Object> vars, Path source, String target) {
        NodeHtml.Replacement fences = n -> fence(n, vars, source, target);
        // Most blocks have no templated fence; their HTML is already written.
        if (!NodeHtml.replacesAny(block.nodes(), n -> templated(n) ? "" : null)) return block.html();
        return NodeHtml.write(block.nodes(), fences);
    }

    /** A fence this writer renders: left as code by loading, with a template for its type. */
    private boolean templated(Node n) {
        String lang = n.props().get("lang");
        return n.type().equals("fence") && !"true".equals(n.props().get("drawn"))
                && lang != null && templates.hasTemplate(lang);
    }

    private String fence(Node n, Map<String, Object> vars, Path source, String target) {
        if (!templated(n)) return null;
        String lang = n.props().get("lang");
        Node code = n.children().stream().filter(c -> "code".equals(c.tag())).findFirst().orElse(null);
        // Extra classes as the info line gave them: the code element's, then the
        // pre's own, once each.
        List<String> classes = new ArrayList<>();
        if (code != null) {
            for (String c : code.classes()) {
                if (!c.startsWith("language-")) classes.add(c);
            }
        }
        for (String c : n.classes()) {
            if (!c.startsWith("language-") && !classes.contains(c)) classes.add(c);
        }
        String id = n.id() != null ? n.id() : code == null ? null : code.id();
        try {
            return templates.template(lang, n.props().get("code"), classes, id, vars, target);
        } catch (BlockTemplates.BlockTemplateException e) {
            throw new LayoutException((source == null ? "" : source + ": ") + "```" + lang + " — "
                    + e.getMessage(), e);
        }
    }
}
