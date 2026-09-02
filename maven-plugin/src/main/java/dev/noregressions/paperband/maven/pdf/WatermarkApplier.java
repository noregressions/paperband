package dev.noregressions.paperband.maven.pdf;

import dev.noregressions.paperband.model.Watermark;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Stamp a {@link Watermark} onto the pages of a finished PDF.
 *
 * <p>Operates as a post-render step: opens the PDF, walks the pages the
 * watermark selects, and draws a transparent overlay on each. The original page
 * content is untouched, so named destinations and page numbering keep working
 * (which keeps the page-count enforcement happy).
 *
 * <p>Implementation notes:
 * <ul>
 *   <li>Text is set in Type1 Helvetica by default, so no font loading or
 *       embedding is required and the resulting PDF stays roughly the same
 *       size. Helvetica is WinAnsi-encoded and cannot render CJK, Cyrillic or
 *       Greek: {@link Watermark#font()} names a TrueType file to embed for
 *       those, and text the resolved font can't encode is reported as a
 *       warning and skipped rather than failing a build that has otherwise
 *       rendered.</li>
 *   <li>Transparency is applied via {@link PDExtendedGraphicsState#setNonStrokingAlphaConstant}
 *       and only affects the non-stroking channel, since we neither stroke the
 *       text nor need a separate alpha for the image.</li>
 *   <li>{@link Watermark#behind()} prepends the overlay instead of appending
 *       it, so the page content paints over the stamp. Invisible at proof
 *       opacities; the reason to reach for it is a heavy mark that would
 *       otherwise sit on top of the body text.</li>
 *   <li>{@link Watermark#fit()} shrinks the stamp until its rotated bounding
 *       box is inside the page. The declared {@code font_size} is then a
 *       ceiling, not a promise — the alternative is a long phrase silently
 *       running off the paper.</li>
 * </ul>
 */
public final class WatermarkApplier {

    private WatermarkApplier() {}

    /** Line height as a multiple of the font size, for a multi-line stamp. */
    private static final float LINE_HEIGHT = 1.2f;

    /** Fraction of the page (or tile cell) a fitted stamp may span. */
    private static final float FIT_FRACTION = 0.92f;

    /** Tile grid, matching the HTML overlay so both outputs read the same. */
    private static final int TILE_COLS = 3;
    private static final int TILE_ROWS = 4;

    /**
     * Apply {@code watermark} to {@code pdf}, writing the result back to the
     * same file. The PDF is read, modified, and saved in place.
     *
     * @param watermark the stamp; null is a no-op
     * @param baseDir   directory that {@link Watermark#image()} and
     *                  {@link Watermark#font()} resolve against — the book root
     * @param warn      where to report a stamp that couldn't be drawn. A
     *                  watermark is a labelling decision, not a structural one:
     *                  the failures here (a missing logo, text the font can't
     *                  set) are worth saying loudly and worth not throwing away
     *                  a rendered book over.
     * @return true if anything was drawn
     * @throws IOException if the file can't be loaded, modified, or saved
     */
    public static boolean apply(Path pdf, Watermark watermark, Path baseDir, Consumer<String> warn)
            throws IOException {
        if (watermark == null) return false;
        Consumer<String> log = warn != null ? warn : m -> { };

        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            PDFont font = null;
            PDImageXObject image = null;

            if (watermark.hasText()) {
                font = resolveFont(doc, watermark, baseDir, log);
                if (font == null) return false;
                String bad = unencodable(font, watermark.lines());
                if (bad != null) {
                    log.accept("watermark text contains " + bad + ", which the watermark font "
                            + "cannot encode — no watermark applied. Point the watermark's "
                            + "'font:' key at a TrueType file that covers those characters.");
                    return false;
                }
            } else {
                image = resolveImage(doc, watermark, baseDir, log);
                if (image == null) return false;
            }

            float[] rgb = parseColor(watermark.color());
            List<PDPage> pages = new ArrayList<>();
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                if (watermark.pages().includes(i)) pages.add(doc.getPage(i));
            }
            if (pages.isEmpty()) {
                log.accept("watermark selected no pages (pages="
                        + watermark.pages() + ", document has " + doc.getNumberOfPages() + ")");
                return false;
            }
            for (PDPage page : pages) {
                stamp(doc, page, watermark, font, image, rgb);
            }
            doc.save(pdf.toFile());
            return true;
        }
    }

    /** Convenience for callers with no book root and no logger. */
    public static boolean apply(Path pdf, Watermark watermark) throws IOException {
        return apply(pdf, watermark, null, null);
    }

    // ---- resources ----

    /**
     * The font to set the text in: the embedded TrueType the watermark names,
     * or one of the two standard Helvetica faces.
     *
     * @return the font, or null when a named font file couldn't be loaded
     */
    private static PDFont resolveFont(
            PDDocument doc, Watermark w, Path baseDir, Consumer<String> warn) {
        if (w.font() == null) {
            return w.bold() ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA;
        }
        Path file = resolve(baseDir, w.font());
        if (!Files.isRegularFile(file)) {
            warn.accept("watermark font not found, no watermark applied: " + file);
            return null;
        }
        try {
            return PDType0Font.load(doc, file.toFile());
        } catch (IOException e) {
            warn.accept("watermark font could not be loaded (" + e.getMessage()
                    + "), no watermark applied: " + file);
            return null;
        }
    }

    /** @return the loaded image, or null when it couldn't be read */
    private static PDImageXObject resolveImage(
            PDDocument doc, Watermark w, Path baseDir, Consumer<String> warn) {
        Path file = resolve(baseDir, w.image());
        if (!Files.isRegularFile(file)) {
            warn.accept("watermark image not found, no watermark applied: " + file);
            return null;
        }
        try {
            return PDImageXObject.createFromFile(file.toString(), doc);
        } catch (IOException e) {
            warn.accept("watermark image could not be read (" + e.getMessage()
                    + "), no watermark applied: " + file);
            return null;
        }
    }

    private static Path resolve(Path baseDir, String ref) {
        Path p = Path.of(ref);
        if (p.isAbsolute() || baseDir == null) return p.normalize();
        return baseDir.resolve(p).normalize();
    }

    /**
     * Characters the font can't encode, as a readable list for a warning.
     *
     * <p>Helvetica throws from {@code getStringWidth} on the first character
     * outside WinAnsi, which as an exception message ("U+673A is not available
     * in this font's encoding") tells an author nothing about which of their
     * words is the problem. This names them all, once.
     *
     * @return a description like {@code '机', '密'}, or null when everything sets
     */
    static String unencodable(PDFont font, List<String> lines) {
        StringBuilder bad = new StringBuilder();
        int found = 0;
        for (String line : lines) {
            for (int i = 0; i < line.length(); ) {
                int cp = line.codePointAt(i);
                String s = new String(Character.toChars(cp));
                i += Character.charCount(cp);
                try {
                    font.getStringWidth(s);
                } catch (IOException | IllegalArgumentException e) {
                    if (bad.indexOf("'" + s + "'") >= 0) continue;
                    if (found > 0) bad.append(", ");
                    bad.append('\'').append(s).append('\'');
                    if (++found == 6) return bad.append(", …").toString();
                }
            }
        }
        return found == 0 ? null : bad.toString();
    }

    // ---- drawing ----

    /** Draw the watermark once per tile position (or once, centred, when not tiling). */
    private static void stamp(
            PDDocument doc, PDPage page, Watermark w, PDFont font, PDImageXObject image, float[] rgb)
            throws IOException {
        PDRectangle box = page.getMediaBox();
        int cols = w.tile() ? TILE_COLS : 1;
        int rows = w.tile() ? TILE_ROWS : 1;
        float cellW = box.getWidth() / cols;
        float cellH = box.getHeight() / rows;

        // PREPEND draws before the page's own content, so the content paints
        // over the stamp. resetContext wraps the existing stream so the
        // watermark's graphics state can't leak into it.
        AppendMode mode = w.behind() ? AppendMode.PREPEND : AppendMode.APPEND;
        try (PDPageContentStream cs = new PDPageContentStream(
                doc, page, mode, /*compress=*/ true, /*resetContext=*/ true)) {

            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(w.opacity());
            cs.setGraphicsStateParameters(gs);
            cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);

            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    float cx = box.getLowerLeftX() + (c + 0.5f) * cellW;
                    float cy = box.getLowerLeftY() + (r + 0.5f) * cellH;
                    if (image != null) {
                        drawImage(cs, image, w, cx, cy, cellW, cellH);
                    } else {
                        drawText(cs, font, w, cx, cy, cellW, cellH);
                    }
                }
            }
        }
    }

    /**
     * Draw the text block centred on {@code (cx, cy)} and rotated about it.
     *
     * <p>Each line is placed on its own baseline within the block, then the
     * whole block is rotated as a unit — rotating the lines individually would
     * fan them out.
     */
    private static void drawText(
            PDPageContentStream cs, PDFont font, Watermark w,
            float cx, float cy, float cellW, float cellH) throws IOException {

        List<String> lines = w.lines();
        // Widths at 1pt, so a change of size is a multiplication rather than a
        // second measuring pass.
        float[] unit = new float[lines.size()];
        float widest = 0f;
        for (int i = 0; i < lines.size(); i++) {
            unit[i] = font.getStringWidth(lines.get(i)) / 1000f;
            widest = Math.max(widest, unit[i]);
        }
        float fontSize = fitted(w, widest, lines.size() * LINE_HEIGHT, cellW, cellH);

        double rad = Math.toRadians(w.angle());
        float blockH = lines.size() * LINE_HEIGHT * fontSize;

        cs.beginText();
        cs.setFont(font, fontSize);
        for (int i = 0; i < lines.size(); i++) {
            // Local block coordinates, origin at the block's centre: lines run
            // top to bottom, each baseline a quarter of the size below its
            // band's middle (cap-height is more precise but adds a font-metrics
            // round trip with no visible benefit at watermark opacities).
            float x = -unit[i] * fontSize / 2f;
            float y = blockH / 2f - (i + 1) * LINE_HEIGHT * fontSize + LINE_HEIGHT * fontSize / 2f
                    - fontSize / 4f;

            AffineTransform at = new AffineTransform();
            at.translate(cx, cy);
            at.rotate(rad);
            at.translate(x, y);
            cs.setTextMatrix(new Matrix(at));
            cs.showText(lines.get(i));
        }
        cs.endText();
    }

    /** Draw the image centred on {@code (cx, cy)}, scaled to the watermark's width fraction. */
    private static void drawImage(
            PDPageContentStream cs, PDImageXObject image, Watermark w,
            float cx, float cy, float cellW, float cellH) throws IOException {

        float aspect = image.getHeight() / (float) image.getWidth();
        float width = w.scale() * cellW;
        float height = width * aspect;
        if (w.fit()) {
            float scale = fitScale(width, height, cellW, cellH, w.angle());
            width *= scale;
            height *= scale;
        }

        // The unit square drawImage() maps: centre it, size it, rotate it,
        // then move it onto the page. AffineTransform applies these in reverse
        // source order, which is exactly that sequence.
        AffineTransform at = new AffineTransform();
        at.translate(cx, cy);
        at.rotate(Math.toRadians(w.angle()));
        at.translate(-width / 2f, -height / 2f);
        at.scale(width, height);
        cs.drawImage(image, new Matrix(at));
    }

    /**
     * The font size to set the block at: the declared size, shrunk until the
     * rotated block fits the cell when {@link Watermark#fit()} is on.
     *
     * @param unitWidth  width of the widest line at 1pt
     * @param unitHeight height of the block at 1pt
     * @return a size no smaller than {@link Watermark#MIN_FONT_SIZE}
     */
    private static float fitted(
            Watermark w, float unitWidth, float unitHeight, float cellW, float cellH) {
        float declared = w.fontSize();
        if (!w.fit() || unitWidth <= 0f) return declared;
        float scale = fitScale(
                unitWidth * declared, unitHeight * declared, cellW, cellH, w.angle());
        return Math.max(Watermark.MIN_FONT_SIZE, declared * scale);
    }

    /**
     * How much a {@code w x h} block rotated by {@code angle} must shrink to sit
     * inside a {@code cellW x cellH} cell.
     *
     * @return a factor in (0, 1]; 1 when it already fits
     */
    static float fitScale(float w, float h, float cellW, float cellH, float angle) {
        double rad = Math.toRadians(angle);
        double cos = Math.abs(Math.cos(rad));
        double sin = Math.abs(Math.sin(rad));
        double boxW = w * cos + h * sin;
        double boxH = w * sin + h * cos;
        if (boxW <= 0 || boxH <= 0) return 1f;
        double scale = Math.min(FIT_FRACTION * cellW / boxW, FIT_FRACTION * cellH / boxH);
        return (float) Math.min(1.0, scale);
    }

    /**
     * Parse a {@code #RRGGBB} or {@code RRGGBB} hex colour to a normalised
     * {@code float[3]}. Falls back to mid-grey for malformed input rather
     * than failing the build.
     */
    static float[] parseColor(String hex) {
        if (hex == null) return new float[]{0.53f, 0.53f, 0.53f};
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() == 3) {
            // Short-form #abc -> #aabbcc
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 3; i++) {
                char c = h.charAt(i);
                sb.append(c).append(c);
            }
            h = sb.toString();
        }
        if (h.length() != 6) return new float[]{0.53f, 0.53f, 0.53f};
        try {
            int r = Integer.parseInt(h.substring(0, 2), 16);
            int g = Integer.parseInt(h.substring(2, 4), 16);
            int b = Integer.parseInt(h.substring(4, 6), 16);
            return new float[]{r / 255f, g / 255f, b / 255f};
        } catch (NumberFormatException e) {
            return new float[]{0.53f, 0.53f, 0.53f};
        }
    }
}
