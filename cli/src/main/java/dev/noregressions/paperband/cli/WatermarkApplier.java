package dev.noregressions.paperband.cli;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Stamp a {@link Watermark} onto every page of a finished PDF.
 *
 * <p>Operates as a post-render step: opens the PDF, walks every page, and
 * appends a transparent text overlay sitting in the page's centre, rotated
 * to the watermark's angle. The original page content is untouched, so
 * named destinations and page numbering keep working (which keeps the
 * page-count enforcement happy).
 *
 * <p>Implementation notes:
 * <ul>
 *   <li>Uses Type1 Helvetica fonts, so no font loading or embedding is
 *       required and the resulting PDF stays roughly the same size.</li>
 *   <li>Transparency is applied via {@link PDExtendedGraphicsState#setNonStrokingAlphaConstant}
 *       and only affects the non-stroking (fill) channel, since we don't
 *       stroke the watermark.</li>
 *   <li>Rotation maths positions the text origin (baseline-left after
 *       rotation) so that the rotated bounding box is centred on the page.</li>
 * </ul>
 */
public final class WatermarkApplier {

    private WatermarkApplier() {}

    /**
     * Apply {@code watermark} to every page of {@code pdf}, writing the result
     * back to the same file. The PDF is read, modified, and saved in place.
     *
     * @throws IOException if the file can't be loaded, modified, or saved
     */
    public static void apply(Path pdf, Watermark watermark) throws IOException {
        if (watermark == null) return;
        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            PDFont font = watermark.bold() ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA;
            float[] rgb = parseColor(watermark.color());
            for (PDPage page : doc.getPages()) {
                drawWatermark(doc, page, font, rgb, watermark);
            }
            doc.save(pdf.toFile());
        }
    }

    private static void drawWatermark(
            PDDocument doc, PDPage page, PDFont font, float[] rgb, Watermark watermark)
            throws IOException {
        PDRectangle box = page.getMediaBox();
        float cx = box.getWidth() / 2f;
        float cy = box.getHeight() / 2f;

        float fontSize = watermark.fontSize();
        // PDFont.getStringWidth returns 1/1000 em units.
        float textWidth = font.getStringWidth(watermark.text()) / 1000f * fontSize;
        // Approximate vertical centring offset: ~quarter of the font size lifts
        // the visible glyph mass to the centre line. Cap-height is more precise
        // but adds a font-loading round trip with no real visible benefit.
        float halfH = fontSize / 4f;
        float halfW = textWidth / 2f;

        double rad = Math.toRadians(watermark.angle());
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        // Position the rotated text so its rotated centre lands on (cx, cy):
        // origin = centre - rotate(halfW, halfH).
        float tx = cx - (cos * halfW - sin * halfH);
        float ty = cy - (sin * halfW + cos * halfH);

        // APPEND mode preserves existing content; both reset and compress flags
        // on so the stamp doesn't carry forward unrelated graphics state.
        try (PDPageContentStream cs = new PDPageContentStream(
                doc, page, AppendMode.APPEND, /*compress=*/ true, /*resetContext=*/ true)) {

            // Transparency for the fill channel.
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(watermark.opacity());
            cs.setGraphicsStateParameters(gs);

            cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2]);
            cs.beginText();
            cs.setFont(font, fontSize);
            cs.setTextMatrix(new Matrix(cos, sin, -sin, cos, tx, ty));
            cs.showText(watermark.text());
            cs.endText();
        }
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
