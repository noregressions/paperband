package dev.noregressions.paperband.block.plantuml;

import dev.noregressions.paperband.block.BlockRenderException;
import dev.noregressions.paperband.block.BlockRenderer;
import dev.noregressions.paperband.block.BlockRequest;

import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.core.Diagram;
import net.sourceforge.plantuml.error.PSystemError;
import net.sourceforge.plantuml.preproc.Defines;
import net.sourceforge.plantuml.security.SFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Draws {@code ```plantuml} fences with PlantUML, at build time, into the page.
 *
 * <p>The diagram becomes an inline {@code <svg>} in the HTML — not a script, not
 * a link to a rendering service, not a file beside the output. That choice is
 * what makes it work everywhere paperband already works: the PDF renderer
 * snapshots a page that is already finished (no {@code window.paperbandPending}
 * wait to honour, unlike {@code ```mermaid}), the static site needs no
 * JavaScript, an offline CI box needs no network, and the diagram scales with
 * the page and prints at the printer's resolution rather than the browser's.
 *
 * <p>The cost is a jar. PlantUML is ~17 MB, which is why this lives in a
 * module of its own that the plugin does not depend on.
 *
 * <h2>What an author writes</h2>
 *
 * <pre>
 * ```plantuml
 * Alice -&gt; Bob: hello
 * Bob --&gt; Alice: hi
 * ```
 * </pre>
 *
 * The {@code @startuml} / {@code @enduml} pair is optional: text that doesn't
 * open with an {@code @start...} directive is wrapped in one, since inside a
 * fence the markers are noise the fence already carries. Any other
 * {@code @start} form ({@code @startmindmap}, {@code @startgantt},
 * {@code @startjson}, {@code @startsalt}, …) is passed through untouched.
 *
 * <h2>Settings</h2>
 *
 * From the {@code plantuml} key of the book's {@code vars} cascade, so they can
 * be set book-wide and overridden per folder or per card:
 *
 * <pre>
 * vars:
 *   plantuml:
 *     format: svg     # svg (default) | png — png embeds as a data: URI
 *     theme: plain    # a PlantUML !theme applied to every diagram
 *     scale: 0.8      # a PlantUML scale applied to every diagram
 *     background: "#fff"   # diagram background; default transparent
 *     styleFile: styles/diagrams.puml   # PlantUML styling, from a file
 *     style: "&lt;style&gt;root { FontName Georgia }&lt;/style&gt;"  # ...or inline
 * </pre>
 *
 * <h2>Making a diagram match the book</h2>
 *
 * <p>Not with CSS. PlantUML bakes every colour into the shape that carries it
 * ({@code fill="#E2E2F0"}, {@code style="stroke:#181818"}) and emits no class
 * attributes at all, so there is nothing for a stylesheet to select — a
 * theme's palette cannot reach the drawing from outside it.
 *
 * <p>PlantUML's own style language can, and {@code styleFile} is where a book
 * puts it: one file, named once in the root {@code paperband.yaml}, applied to
 * every diagram in the book.
 *
 * <pre>
 * &lt;style&gt;
 * root { FontName "IBM Plex Sans"; FontColor #333333; LineColor #aeb7c2 }
 * sequenceDiagram { participant { BackgroundColor #f6f8fa; LineColor #1d4ed8 } }
 * &lt;/style&gt;
 * </pre>
 *
 * <h2>Errors</h2>
 *
 * PlantUML does not throw on a diagram it cannot parse; it draws a picture of
 * the error and returns it, which in a book means a page that builds green and
 * prints a screenshot of a stack trace. So the result is inspected, and a bad
 * diagram fails the build with PlantUML's own message — the same stance
 * {@code ```mermaid} takes.
 */
public final class PlantumlBlockRenderer implements BlockRenderer {

    /** The fence tags an author might plausibly type for a PlantUML diagram. */
    private static final Set<String> TYPES = Set.of("plantuml", "puml", "uml");

    /** Every {@code @start...} PlantUML understands is spelled with this prefix. */
    private static final String START = "@start";

    @Override
    public String name() {
        return "plantuml";
    }

    @Override
    public String description() {
        return "PlantUML diagrams rendered to inline SVG at build time (no browser, no network).";
    }

    @Override
    public Set<String> types() {
        return TYPES;
    }

    @Override
    public String render(BlockRequest request) {
        String format = request.setting("format", "svg").toLowerCase(Locale.ROOT);
        FileFormat fileFormat = switch (format) {
            case "svg" -> FileFormat.SVG;
            case "png" -> FileFormat.PNG;
            default -> throw new BlockRenderException(
                    "unknown format '" + format + "' — vars.plantuml.format is svg or png");
        };

        String source = wrap(request.content(), request);

        // A diagram may !include a file beside the card that wrote it, so the
        // reader's current directory is the card's, not the build's.
        Path dir = request.source() == null ? null : request.source().toAbsolutePath().getParent();
        SourceStringReader reader = new SourceStringReader(
                Defines.createEmpty(), source, StandardCharsets.UTF_8, List.of(),
                dir == null ? null : new SFile(dir.toString()));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            reader.outputImage(out, new FileFormatOption(fileFormat));
        } catch (IOException e) {
            throw new BlockRenderException("PlantUML failed to render: " + e.getMessage(), e);
        }
        failOnDiagramError(reader);

        String body = fileFormat == FileFormat.SVG
                ? inlineSvg(out.toString(StandardCharsets.UTF_8))
                : "<img alt=\"PlantUML diagram\" src=\"data:image/png;base64,"
                        + Base64.getEncoder().encodeToString(out.toByteArray()) + "\">";
        return figure(body, request);
    }

    /**
     * Add what the fence already implies, and what the book asked for.
     *
     * <p>Directives go inside the {@code @startuml}, not before it — PlantUML
     * only reads them once a diagram is open.
     */
    private static String wrap(String content, BlockRequest request) {
        String body = content == null ? "" : content.strip();
        String theme = request.setting("theme", null);
        String scale = request.setting("scale", null);
        // Transparent by default: a diagram is part of the page, and PlantUML's
        // own #FFFFFF is a white rectangle sitting on whatever paper the theme
        // paints. A book that wants the box back names a colour.
        String background = request.setting("background", "transparent");

        // Broadest first, so the narrower thing wins: a bundled !theme, then
        // our background default, then the book's style file, then whatever
        // one card said inline.
        StringBuilder directives = new StringBuilder();
        if (theme != null) directives.append("!theme ").append(theme).append('\n');
        directives.append("skinparam backgroundColor ").append(background).append('\n');
        request.file("styleFile").ifPresent(f -> directives.append(read(f)).append('\n'));
        String style = request.setting("style", null);
        if (style != null) directives.append(style).append('\n');
        if (scale != null) directives.append("scale ").append(scale).append('\n');

        if (body.startsWith(START)) {
            // Slip them in after the @start line, whichever @start it is.
            int nl = body.indexOf('\n');
            return nl < 0 ? body : body.substring(0, nl + 1) + directives + body.substring(nl + 1);
        }
        return "@startuml\n" + directives + body + "\n@enduml";
    }

    /**
     * A style file, verbatim.
     *
     * <p>Not wrapped in anything: PlantUML has two styling syntaxes that both
     * belong in such a file — the {@code <style>} block and the older
     * {@code skinparam} lines — and a wrapper would rule one of them out.
     * What the file says is what the diagram gets, which also means
     * {@code !include} and {@code !theme} work in there.
     */
    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BlockRenderException("could not read style file " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * PlantUML reports a bad diagram by drawing one. Catch that and throw,
     * with the message it would have drawn.
     */
    private static void failOnDiagramError(SourceStringReader reader) {
        if (reader.getBlocks().isEmpty()) {
            throw new BlockRenderException(
                    "PlantUML found no diagram in this block. A fence whose text does not start"
                            + " with @start... is wrapped in @startuml/@enduml, so this usually"
                            + " means an @start directive with no matching @end.");
        }
        Diagram diagram = reader.getBlocks().get(0).getDiagram();
        if (diagram instanceof PSystemError error) {
            String message = error.getWarningOrError();
            throw new BlockRenderException(message == null || message.isBlank()
                    ? "PlantUML could not parse this diagram."
                    : message.strip());
        }
    }

    /**
     * PlantUML's SVG is a standalone document; the page wants an element.
     *
     * <p>Three edits, each one a thing that would otherwise be wrong on paper:
     *
     * <ul>
     *   <li>The XML prolog goes, so the markup can be spliced into HTML — as
     *       does the {@code <?plantuml?>} processing instruction, which an
     *       HTML parser renders as a stray comment.</li>
     *   <li>The pixel size stays on the {@code width}/{@code height}
     *       <em>attributes</em> (a diagram should print at the size it was
     *       drawn) but comes off the inline {@code style}, where it would
     *       beat the {@code height: auto} that lets a too-wide diagram shrink
     *       to the column instead of overflowing it.</li>
     *   <li>{@code preserveAspectRatio} becomes {@code xMidYMid meet}.
     *       PlantUML emits {@code none}, which is right for a diagram nothing
     *       will ever resize and wrong for one in a book: shrunk to the
     *       measure with {@code none}, the drawing is squashed rather than
     *       scaled.</li>
     * </ul>
     */
    private static String inlineSvg(String svg) {
        int start = svg.indexOf("<svg");
        if (start < 0) {
            throw new BlockRenderException("PlantUML returned something that is not an SVG.");
        }
        String element = svg.substring(start).replaceAll("<\\?plantuml[^?]*\\?>", "");
        int end = element.indexOf('>');
        if (end < 0) return element;
        String open = element.substring(0, end);
        String rest = element.substring(end);
        open = open.replaceAll("(?i)(width|height)\\s*:\\s*[^;\"]*;?", "")
                .replaceAll("style=\"\\s*\"", "")
                .replaceAll("(?i)\\spreserveAspectRatio=\"[^\"]*\"", "")
                + " preserveAspectRatio=\"xMidYMid meet\"";
        return open + rest;
    }

    /**
     * The wrapper every diagram lands in.
     *
     * <p>A {@code figure} rather than a bare {@code svg}: themes already style
     * figures (centred, spaced, {@code break-inside: avoid} for print), so a
     * diagram inherits the book's treatment of pictures instead of needing its
     * own. The {@code plantuml} class is the hook for anything specific to
     * these; the author's own info-line classes ride alongside it.
     */
    private static String figure(String body, BlockRequest request) {
        Set<String> classes = new LinkedHashSet<>();
        classes.add("plantuml");
        classes.addAll(request.classes());
        return "<figure class=\"" + String.join(" ", classes) + "\""
                + (request.id() == null ? "" : " id=\"" + escapeAttr(request.id()) + "\"")
                + ">" + body + "</figure>";
    }

    private static String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
