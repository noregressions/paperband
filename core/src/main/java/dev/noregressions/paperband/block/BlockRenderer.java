package dev.noregressions.paperband.block;

import java.util.Set;

/**
 * A fenced code block rendered by code rather than by a template.
 *
 * <p>Paperband already lets a fence type choose its own markup: {@code ```mermaid}
 * and friends resolve to a Pebble fragment at {@code blocks/&lt;type&gt;.html}
 * (see {@code BlockTemplates} in the cards module). That covers everything a
 * template can express — wrap the text, add classes, hand it to a browser
 * script. It does not cover a block whose content has to be <em>computed</em>:
 * a diagram rasterised at build time, a table read out of a database, source
 * run through a real parser. That needs a jar, and a jar means an optional
 * dependency the core build must not carry.
 *
 * <p>So this is the second half of the same idea, discovered the way renderers
 * are: implement it, register the class in
 * {@code META-INF/services/dev.noregressions.paperband.block.BlockRenderer},
 * and put the jar on the plugin's classpath (a {@code <dependency>} inside the
 * {@code <plugin>} element). Nothing else in the book changes — the fence was
 * already valid markdown, and stays valid markdown for every editor that will
 * never have the jar.
 *
 * <h2>Precedence</h2>
 *
 * A book's own {@code layouts/blocks/&lt;type&gt;.html} — and a theme's — wins
 * over a registered renderer, which in turn wins over a bundled block
 * template. The author on the machine always outranks the jar; the jar
 * outranks the default. That ordering is what lets a book override one
 * diagram with hand-written markup without uninstalling anything, and lets a
 * module take over a bundled type ({@code mermaid} rendered server-side, say)
 * by claiming it.
 *
 * <h2>Capabilities</h2>
 *
 * An implementation declares three things about itself: the {@link #types()}
 * it claims, whether it {@link #isAvailable() works here}, and — when it
 * doesn't — {@link #unavailableReason() why not}. {@code mvn paperband:blocks}
 * prints all three, so "my diagrams came out as code blocks" is one command to
 * diagnose rather than a guess about classpaths.
 *
 * <p>Implementations must be thread-safe and cheap to construct: one instance
 * is discovered per build and shared across every card.
 */
public interface BlockRenderer {

    /**
     * This renderer's name — its identity in diagnostics, and the key its
     * configuration sits under in the book's {@code vars} cascade
     * ({@code vars: { plantuml: { format: svg } }} reaches a renderer named
     * {@code plantuml} as {@link BlockRequest#config()}).
     *
     * <p>Lowercase, no spaces. Distinct from {@link #types()}: one renderer
     * may claim several fence tags, and its configuration is still one block.
     *
     * @return the renderer's name
     */
    String name();

    /** One line for {@code mvn paperband:blocks}. What it renders, and how. */
    String description();

    /**
     * The fence tags this renderer claims — {@code ```plantuml},
     * {@code ```puml}, {@code ```uml} for one PlantUML renderer.
     *
     * <p>Matched case-sensitively against the fence's language tag, which is
     * also what a reader sees in the source, so claim what an author would
     * plausibly type. Two renderers claiming the same tag is a build failure
     * rather than a silent race — see {@link BlockRendererRegistry}.
     *
     * @return the tags, never empty
     */
    Set<String> types();

    /**
     * Whether this renderer can actually run on this machine.
     *
     * <p>Default true: a pure-Java renderer is available wherever its jar is.
     * Override when the backend needs something the classpath can't
     * guarantee — an external binary, a reachable server, a licence file — and
     * say so in {@link #unavailableReason()}.
     *
     * <p>An unavailable renderer does not fail the build by existing. It
     * declines the block, which then falls through to a bundled template or to
     * an ordinary code block: a book still builds on a machine that can't draw
     * its diagrams, with the source visible where each one would have been.
     *
     * @return true when {@link #render} can be expected to work
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Why {@link #isAvailable()} is false, phrased as something to do about
     * it ("graphviz not found: install it or set GRAPHVIZ_DOT"). Null when
     * available.
     *
     * @return the reason, or null
     */
    default String unavailableReason() {
        return null;
    }

    /**
     * Render one block to the HTML that replaces it.
     *
     * <p>The returned fragment is parsed and spliced into the card in place of
     * the {@code <pre><code>}, so it must be well-formed and self-contained —
     * no {@code <script>} that assumes a loader, no ids that could collide
     * across cards unless the author asked for one via
     * {@link BlockRequest#id()}.
     *
     * @param request the block and its context
     * @return the replacement HTML; null to decline the block, leaving it an
     *         ordinary code block
     * @throws BlockRenderException when the block is this renderer's to handle
     *         and is wrong — a diagram that doesn't parse is a build failure,
     *         not a blank space on a page
     */
    String render(BlockRequest request) throws BlockRenderException;
}
