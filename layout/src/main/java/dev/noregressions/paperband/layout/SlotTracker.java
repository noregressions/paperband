package dev.noregressions.paperband.layout;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-card placement accounting for slot-based ("structural") card templates.
 *
 * <p>Every full card model carries one of these as {@code card.slots}. A
 * template that wants to control block order — rather than looping
 * {@code card.blocks} in document order — <em>places</em> blocks into a fixed
 * skeleton by pulling them out of the tracker:
 *
 * <pre>
 * {% for b in card.slots.take('intro') %}{% include "_block-section" with {"block": b} %}{% endfor %}
 * {% for b in card.slots.require('check') %}{% include "_block-section" with {"block": b} %}{% endfor %}
 * {% for b in card.slots.rest() %}{% include "_block-section" with {"block": b} %}{% endfor %}
 * </pre>
 *
 * <p>Matching is by the block's explicit {@code id} or any of its CSS classes
 * (auto-slugged headings put the slug in the class set, so {@code ## Watch Out}
 * matches the name {@code watch-out}). A name argument may be a single string
 * or a Pebble list literal for aliases: {@code take(['watch-out','gotchas'])}.
 *
 * <p>Consumption is first-take-wins over <b>top-level</b> blocks only —
 * nested blocks always travel with their parent, rendered by
 * {@code _block-section}'s own recursion. After the card renders, the engine
 * asks {@link #used()}/{@link #unplaced()}/{@link #missingRequired()}: if the
 * template used slots at all, every top-level block must have been consumed
 * (by a named slot or the {@link #rest()} catch-all) and every
 * {@code require(...)} must have matched, or the build fails with
 * {@link SlotPlacementException}. A template that never touches the tracker
 * (the default looping {@code _card-body-base}) is never checked — slots are
 * opt-in per template.
 *
 * <h2>Layout hints ({@link #takeWithLayout}/{@link #requireWithLayout})</h2>
 * A slot-based template already hand-writes a wrapper element per named
 * region (e.g. margin-notes' {@code <div class="mn-main">}/{@code <aside
 * class="mn-rail">}) — pagewright doesn't invent the box arrangement, a
 * theme author does. What a template can't easily hand-write per build is a
 * <em>declarative</em> size preference or page-break preference that varies
 * by page config rather than being baked into theme CSS. {@code
 * takeWithLayout}/{@code requireWithLayout} take the same name argument as
 * {@code take}/{@code require} plus a hint map ({@code {height: [min, max],
 * break: 'avoid-split'|'page-start'}}, both keys optional) and return a
 * {@link PlacedSlot} carrying a ready-to-use inline {@code style} string
 * (custom properties {@code --slot-height-min}/{@code --slot-height-max} a
 * theme's own CSS can consume, e.g. {@code max-height:
 * var(--slot-height-max, none)}) and a {@code breakClass} ({@code
 * pw-avoid-split} maps to {@code break-inside: avoid}, {@code pw-page-start}
 * to {@code break-before: page} — both bundled in every theme's scaffold
 * CSS). Nothing is mutated on the block maps themselves, so this is safe to
 * mix with plain {@code take}/{@code require} calls or reuse of the same
 * card model elsewhere.
 */
public final class SlotTracker {

    /**
     * Result of {@link #takeWithLayout}/{@link #requireWithLayout}: the
     * consumed blocks (empty if the slot wasn't present — a template should
     * skip rendering its wrapper entirely in that case, same as checking
     * {@code take(...)|length} today) plus a ready-to-use inline style
     * fragment and break-preference CSS class.
     *
     * @param blocks     consumed top-level blocks for this slot, in document order
     * @param style      inline CSS custom-property declarations for the height hint
     *                   (empty string if no {@code height} key was given)
     * @param breakClass {@code pw-avoid-split}, {@code pw-page-start}, or empty string
     */
    public record PlacedSlot(List<Map<String, Object>> blocks, String style, String breakClass) {

        /** True when this slot had no matching blocks — template should render nothing. */
        public boolean isEmpty() {
            return blocks.isEmpty();
        }
    }

    private final List<Map<String, Object>> blocks;
    private final Set<Integer> consumed = new HashSet<>();
    private final List<String> missing = new ArrayList<>();
    private boolean used;

    SlotTracker(List<Map<String, Object>> blocks) {
        this.blocks = blocks;
    }

    /**
     * Consume and return every not-yet-consumed top-level block whose id or
     * class set matches any of {@code names}, in document order.
     */
    public List<Map<String, Object>> take(Object names) {
        used = true;
        return consume(nameList(names));
    }

    /**
     * Like {@link #take}, but records a structural violation when nothing
     * matches — for sections every card is required to have.
     */
    public List<Map<String, Object>> require(Object names) {
        used = true;
        List<String> ns = nameList(names);
        List<Map<String, Object>> got = consume(ns);
        if (got.isEmpty()) missing.add(String.join("|", ns));
        return got;
    }

    /**
     * The optional catch-all: consume and return everything not yet consumed,
     * in document order. A template without one fails on any unexpected block.
     */
    public List<Map<String, Object>> rest() {
        used = true;
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            if (consumed.add(i)) out.add(blocks.get(i));
        }
        return out;
    }

    /**
     * Like {@link #take}, but also returns a size/break-preference hint as a
     * {@link PlacedSlot} instead of a bare block list. {@code hint} keys are
     * both optional: {@code height} is a two-element {@code [min, max]}
     * percentage list, {@code break} is {@code "avoid-split"} or
     * {@code "page-start"}. A {@code hint} of {@code null} behaves exactly
     * like plain {@link #take}, wrapped.
     */
    public PlacedSlot takeWithLayout(Object names, Map<String, Object> hint) {
        return toPlacedSlot(take(names), hint);
    }

    /** Like {@link #takeWithLayout}, but records a structural violation when nothing matches (see {@link #require}). */
    public PlacedSlot requireWithLayout(Object names, Map<String, Object> hint) {
        return toPlacedSlot(require(names), hint);
    }

    private static PlacedSlot toPlacedSlot(List<Map<String, Object>> blocks, Map<String, Object> hint) {
        if (hint == null || blocks.isEmpty()) {
            return new PlacedSlot(blocks, "", "");
        }
        return new PlacedSlot(blocks, styleFor(hint.get("height")), breakClassFor(hint.get("break")));
    }

    private static String styleFor(Object heightNode) {
        if (!(heightNode instanceof Collection<?> c) || c.size() != 2) return "";
        Iterator<?> it = c.iterator();
        Object min = it.next();
        Object max = it.next();
        return "--slot-height-min:" + min + "%;--slot-height-max:" + max + "%;";
    }

    private static String breakClassFor(Object breakNode) {
        if (breakNode == null) return "";
        String s = breakNode.toString().trim();
        return switch (s) {
            case "avoid-split" -> "pw-avoid-split";
            case "page-start"  -> "pw-page-start";
            case "flexible", "" -> "";
            default -> throw new IllegalArgumentException(
                    "card.slots: unknown break preference '" + s
                    + "' (expected avoid-split, page-start, or flexible)");
        };
    }

    /**
     * Non-consuming peek: is there an unconsumed block matching any of
     * {@code names}? The hook for structure-dependent layout branching
     * ({@code {% if card.slots.has('diffs') %}}) — never affects accounting.
     */
    public boolean has(Object names) {
        List<String> ns = nameList(names);
        for (int i = 0; i < blocks.size(); i++) {
            if (!consumed.contains(i) && matches(blocks.get(i), ns)) return true;
        }
        return false;
    }

    // ---- engine-side accounting (not for templates) ----

    boolean used() {
        return used;
    }

    List<Map<String, Object>> unplaced() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            if (!consumed.contains(i)) out.add(blocks.get(i));
        }
        return out;
    }

    List<String> missingRequired() {
        return missing;
    }

    // ---- internals ----

    private List<Map<String, Object>> consume(List<String> names) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            if (consumed.contains(i)) continue;
            if (matches(blocks.get(i), names)) {
                consumed.add(i);
                out.add(blocks.get(i));
            }
        }
        return out;
    }

    private static boolean matches(Map<String, Object> block, List<String> names) {
        Object id = block.get("id");
        Object classes = block.get("classes");
        for (String n : names) {
            if (n.equals(id)) return true;
            if (classes instanceof Collection<?> cs && cs.contains(n)) return true;
        }
        return false;
    }

    private static List<String> nameList(Object names) {
        if (names instanceof String s && !s.isBlank()) return List.of(s);
        if (names instanceof Collection<?> c && !c.isEmpty()) {
            List<String> out = new ArrayList<>(c.size());
            for (Object o : c) out.add(String.valueOf(o));
            return out;
        }
        throw new IllegalArgumentException(
                "card.slots: expected a slot name or non-empty list of names, got: " + names);
    }
}
