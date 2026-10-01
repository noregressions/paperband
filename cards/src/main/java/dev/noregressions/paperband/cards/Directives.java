package dev.noregressions.paperband.cards;

import dev.noregressions.paperband.model.CardNumber;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.CustomNode;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.PostProcessor;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlNodeRendererFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@code {!name}} anywhere in the text: a marker paperband replaces with
 * something it works out, such as {@code {!step}} becoming "Step 2".
 *
 * <pre>
 * # {!step} Build             →  Step 2 Build
 * Run it again ({!step}).     →  Run it again (Step 3).
 * Scenario {!number} recap    →  Scenario 3 recap
 * </pre>
 *
 * <p>{@code {!number}} is the card's own number, bare: "3" for Scenario 3,
 * "2.4" for a chapter. Nothing knows it while a card is read, so it renders
 * as {@link CardNumber#MARK} and the layout fills it in.
 *
 * <p>A marker sits wherever the author wants the result to read, so it's found
 * in ordinary text only: never inside code, raw HTML or a fence, where
 * {@code {!step}} is an example rather than an instruction. This pass runs
 * before {@link AttributeSyntax}, so a marker at the end of a heading is still
 * a marker, not an attribute group.
 *
 * <p>An unknown name fails the build, so a misspelt marker can't be silently
 * printed. {@code !name} inside an attribute group ({@code {.x !step}}, or on
 * a fence's info line) is the same instruction without the text: see
 * {@link AttributeSyntax}.
 */
final class Directives implements PostProcessor {

    /** Directives paperband knows, and whether each takes a value. */
    static final Map<String, Boolean> KNOWN = Map.of(
            "step", false,      // numbered by position -- see Steps
            "number", false);   // the card's own number, filled in when the book is assembled

    /** The directive that prints the card's own number. */
    static final String NUMBER = "number";

    private static final Pattern MARKER = Pattern.compile("\\{!([a-z][a-z0-9-]*)(?:=([^{}\\s]+))?\\}");

    /**
     * A bare {@code {word}}: shaped like a marker, but with no {@code !}, {@code .}
     * or {@code /} to say which kind. It's almost always one mistyped --
     * {@code {step}} for {@code {!step}} -- so it fails rather than printing.
     * Braces holding anything else (spaces, commas, a {@code ${}} placeholder)
     * are prose and stay as text.
     */
    private static final Pattern BARE = Pattern.compile("(?<![{$\\w])\\{([A-Za-z][\\w.-]*)\\}(?!\\})");

    /** A {@code {!name}} in the text; renders as whatever its pass set as its text. */
    static final class Marker extends CustomNode {
        private final String name;
        private String text = "";

        Marker(String name) {
            this.name = name;
        }

        String name() {
            return name;
        }

        void setText(String text) {
            this.text = text;
        }

        String text() {
            return text;
        }
    }

    @Override
    public Node process(Node document) {
        List<Text> texts = new ArrayList<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                texts.add(text);
            }
        });
        for (Text text : texts) split(text);
        return document;
    }

    /** Replace each marker in {@code text} with a {@link Marker} node between the surrounding text. */
    private static void split(Text text) {
        String literal = text.getLiteral();
        Matcher bare = BARE.matcher(literal);
        if (bare.find()) {
            String name = bare.group(1);
            throw new IllegalArgumentException("unknown marker '{" + name + "}'."
                    + (KNOWN.containsKey(name) ? " Did you mean {!" + name + "}?" : "")
                    + " A marker in braces needs a ! for an instruction ({!step}), a . for a class"
                    + " ({." + name + "}) or a / to end one ({/" + name + "}). To print braces, put"
                    + " them in backticks.");
        }
        Matcher m = MARKER.matcher(literal);
        if (!m.find()) return;
        int from = 0;
        do {
            String name = m.group(1);
            check(name, m.group(2));
            if (m.start() > from) text.insertBefore(new Text(literal.substring(from, m.start())));
            text.insertBefore(new Marker(name));
            from = m.end();
        } while (m.find());
        if (from < literal.length()) text.insertBefore(new Text(literal.substring(from)));
        text.unlink();
    }

    /**
     * @throws IllegalArgumentException for a name paperband doesn't know, or a
     *         value given to a directive that takes none
     */
    static void check(String name, String value) {
        Boolean takesValue = KNOWN.get(name);
        if (takesValue == null) {
            throw new IllegalArgumentException("unknown directive '{!" + name
                    + "}'. Known directives: " + KNOWN.keySet().stream().sorted()
                    .map(d -> "{!" + d + "}").collect(Collectors.joining(", ")) + ".");
        }
        if (!takesValue && value != null) {
            throw new IllegalArgumentException("directive '{!" + name + "}' takes no value (got '"
                    + value + "'). Write {!" + name + "}: the position is the number.");
        }
    }

    /** Renders each marker as its text. */
    static HtmlNodeRendererFactory renderer() {
        return MarkerRenderer::new;
    }

    private static final class MarkerRenderer implements NodeRenderer {
        private final HtmlNodeRendererContext context;

        MarkerRenderer(HtmlNodeRendererContext context) {
            this.context = context;
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Marker.class);
        }

        @Override
        public void render(Node node) {
            Marker marker = (Marker) node;
            context.getWriter().text(NUMBER.equals(marker.name()) ? CardNumber.MARK : marker.text());
        }
    }
}
