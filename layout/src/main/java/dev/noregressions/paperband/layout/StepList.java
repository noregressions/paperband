package dev.noregressions.paperband.layout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A card's steps as a list: every {@code {!step}} block, flattened in document
 * order with its depth, which a template sees as {@code card.steps}. It's a
 * fact about the card. What to do with it -- the cheatsheet view keeps only
 * the cards that have steps, and prints one entry per step -- is the view's
 * business, in its templates.
 */
final class StepList {

    private StepList() {
    }

    /**
     * Every stepped block among {@code blocks} and their children, flattened in
     * document order: each entry is {@code {block, depth}}, where depth counts
     * the stepped blocks above it (0 for a top-level step). A template lists
     * them without recursing, and a card's steps are known before any HTML is
     * written: {@code {% if card.steps is empty %}}.
     *
     * @param blocks the card's block models, as {@code LayoutEngine} builds them
     */
    static List<Map<String, Object>> steps(List<Map<String, Object>> blocks) {
        List<Map<String, Object>> out = new ArrayList<>();
        collect(blocks, 0, out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void collect(List<Map<String, Object>> blocks, int depth, List<Map<String, Object>> out) {
        for (Map<String, Object> b : blocks) {
            boolean stepped = b.get("directives") instanceof Map<?, ?> d && d.get("step") != null;
            if (stepped) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("block", b);
                entry.put("depth", depth);
                out.add(entry);
            }
            if (b.get("children") instanceof List<?> children) {
                collect((List<Map<String, Object>>) children, stepped ? depth + 1 : depth, out);
            }
        }
    }
}
