package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.model.Block;
import dev.noregressions.paperband.model.Card;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cheat-sheet mode: a book, or a site, made of each card's steps rather than
 * the whole card. Switched on by {@code vars.cheatsheet}, which cascades like
 * any var, so a POM execution's {@code <book><vars>} turns it on for one build
 * while the same cards stay a full guide in every other.
 *
 * <pre>
 * vars:
 *   cheatsheet: true                           # the defaults below
 *   cheatsheetSelect: "p.instructions, pre.console"  # what each step contributes
 *
 *   cheatsheet:                                # or both at once, in yaml
 *     select: "p.instructions, pre.console"
 * </pre>
 *
 * <p>The flat {@code cheatsheetSelect} exists because a POM's
 * {@code <book><vars>} is a string map: {@code <cheatsheet>true</cheatsheet>}
 * and {@code <cheatsheetSelect>...</cheatsheetSelect>} are how an execution
 * sets both.
 *
 * <p>In this mode a card's body is {@code _cheatsheet-card.html} in place of
 * {@code _card-body.html}: one entry per {@code {!step}}, its heading plus the
 * parts of its content matching {@link #DEFAULT_SELECT} or the book's own
 * selector. Cards flow on rather than each starting a page, and a card with no
 * steps is left out of the build altogether, the way a {@code select:} leaves
 * one out, so a {@code card:} link to it becomes plain text.
 */
public final class CheatSheet {

    /**
     * What each step contributes when the book doesn't say: the paragraph an
     * author marked {@code {.instructions}}, and the command or console session
     * that goes with it.
     */
    public static final String DEFAULT_SELECT = ".instructions, pre.command, pre.console";

    private CheatSheet() {
    }

    /**
     * Whether {@code vars} switch cheat-sheet mode on: {@code cheatsheet: true},
     * or a {@code cheatsheet:} map that doesn't say {@code enabled: false}.
     */
    public static boolean enabled(Map<String, Object> vars) {
        if (vars == null) return false;
        Object v = vars.get("cheatsheet");
        if (v instanceof Boolean b) return b;
        if (v instanceof Map<?, ?> m) return !Boolean.FALSE.equals(m.get("enabled"))
                && !"false".equals(String.valueOf(m.get("enabled")));
        return v != null && "true".equalsIgnoreCase(v.toString().strip());
    }

    /**
     * The template's view of the mode for one card: null when it's off, else
     * {@code {select: "..."}} with the book's selector or the default.
     */
    static Map<String, Object> model(Map<String, Object> vars) {
        if (!enabled(vars)) return null;
        String select = DEFAULT_SELECT;
        Object flat = vars.get("cheatsheetSelect");
        if (vars.get("cheatsheet") instanceof Map<?, ?> m && m.get("select") != null
                && !m.get("select").toString().isBlank()) {
            select = m.get("select").toString();
        } else if (flat != null && !flat.toString().isBlank()) {
            select = flat.toString();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("select", select);
        return out;
    }

    /** Whether any block of {@code card}, at any depth, carries {@code {!step}}. */
    public static boolean hasSteps(Card card) {
        return hasSteps(card.blocks());
    }

    private static boolean hasSteps(List<Block> blocks) {
        for (Block b : blocks) {
            if (b.directives().containsKey("step") || hasSteps(b.children())) return true;
        }
        return false;
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
