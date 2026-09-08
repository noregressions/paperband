package dev.noregressions.paperband.render.pptx;

import org.apache.poi.sl.usermodel.Insets2D;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.sl.usermodel.VerticalAlignment;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.openxmlformats.schemas.drawingml.x2006.main.CTSRgbColor;
import org.openxmlformats.schemas.drawingml.x2006.main.CTSolidColorFillProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTransform2D;
import org.openxmlformats.schemas.presentationml.x2006.main.CTBackground;
import org.openxmlformats.schemas.presentationml.x2006.main.CTBackgroundProperties;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape;
import org.openxmlformats.schemas.presentationml.x2006.main.CTSlide;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns harvested cards into a PowerPoint file.
 *
 * <p>Text fills typed placeholders; each figure becomes a floating picture at
 * the rect it occupied. Pictures are pinned rather than placed in a layout's
 * PICTURE placeholder on purpose — a diagram has a definite size that came from
 * the browser, and there is no stock layout whose picture box happens to match
 * it. Prose keeps the placeholder semantics that make the deck feel native;
 * figures keep their geometry.
 *
 * <p>Separate from {@link PptxRenderer} so that everything downstream of the
 * browser is reachable without one: the harvest is the only part that needs
 * Chromium, and it would be a poor trade to leave the layout mapping,
 * placeholder filling and text styling untested because of it. Tests drive
 * this class with hand-written card maps of the shape
 * {@link SlideHarvest#harvest} returns.
 *
 * <p>The card map shape, for reference:
 * <pre>
 * {id, background,
 *  title:    {text, rect:{x,y,w,h}, style:{...}} | null,
 *  subtitle: {text, rect, style}                 | null,
 *  body:     {rect, paras:[{bullet, runs:[{text, style}], style}],
 *             figures:[{id, kind, rect}]}         | null,
 *  aside:    same as body                        | null,
 *  notes:    String | null,
 *  unplaced: String | null}
 * </pre>
 */
final class DeckWriter {

    /** CSS px to PostScript points. Paperband lays out at 96 px/in. */
    static final double PT = 72.0 / 96.0;

    /** English Metric Units per point. */
    static final int EMU = 12_700;

    private static final Pattern RGB = Pattern.compile(
            "rgba?\\(\\s*(\\d+)[,\\s]+(\\d+)[,\\s]+(\\d+)(?:[,/\\s]+([\\d.]+))?");

    /**
     * What the write produced, for the build log and for tests.
     *
     * @param slides    number of slides written
     * @param layoutUse layout name to slide count, insertion-ordered
     * @param unplaced  cards that had a block no slot claimed
     * @param pictures  figures placed as pictures across the whole deck
     */
    record Result(int slides, Map<String, Integer> layoutUse, List<String> unplaced,
                  int pictures) {}

    private DeckWriter() {}

    /**
     * Write {@code cards} as a deck.
     *
     * @param figures  captured figure images, keyed by the harvest's figure id;
     *                 a figure with no entry is warned about and skipped
     * @param widthPx  page width in CSS px
     * @param heightPx page height in CSS px
     * @param onWarn   receives one message per card with unplaced blocks, and one
     *                 per figure that could not be placed; may be null
     * @throws IllegalArgumentException from {@link LayoutMap} when a card's slot
     *         signature has no matching PowerPoint layout
     */
    static Result write(List<Map<String, Object>> cards, Map<String, byte[]> figures,
                        int widthPx, int heightPx,
                        Path output, Consumer<String> onWarn) throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.setPageSize(new Dimension(
                    (int) Math.round(widthPx * PT), (int) Math.round(heightPx * PT)));
            XSLFSlideMaster master = ppt.getSlideMasters().get(0);
            Map<String, Integer> layoutUse = new LinkedHashMap<>();
            List<String> unplaced = new ArrayList<>();
            int pictures = 0;

            for (Map<String, Object> card : cards) {
                String id = str(card.get("id"));
                LayoutMap.Signature sig = signatureOf(card);
                String layoutName = LayoutMap.forSignature(sig, id);
                layoutUse.merge(layoutName + "  <- " + sig.key(), 1, Integer::sum);

                XSLFSlide slide = ppt.createSlide(master.getLayout(layoutName));
                setBackground(slide, cssColor(str(card.get("background"))));

                fillSlide(slide, card);
                pictures += placeFigures(ppt, slide, card, figures, onWarn);

                String notes = notesFor(card);
                if (notes != null) attachNotes(ppt, slide, notes, onWarn);

                if (str(card.get("unplaced")) != null) {
                    unplaced.add(id);
                    if (onWarn != null) {
                        onWarn.accept("Card '" + id + "': a block matched no slot and was"
                                + " routed to speaker notes — \""
                                + ellipsis(str(card.get("unplaced")))
                                + "\". Name it in the deck template's slot skeleton if it"
                                + " belongs on the slide.");
                    }
                }
            }

            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (OutputStream os = Files.newOutputStream(output)) {
                ppt.write(os);
            }
            return new Result(cards.size(), layoutUse, List.copyOf(unplaced), pictures);
        }
    }

    /**
     * Paint one slide's own ground.
     *
     * <p>Not {@code slide.getBackground().setFillColor()}: for a slide with no
     * background of its own, POI hands back the <em>master's</em> background
     * object, so that call silently repaints every slide in the deck. It looks
     * correct while a theme gives every card the same ground and goes wrong the
     * moment one card differs — the last one written wins everywhere. Writing
     * {@code cSld/bg} on this slide keeps it local.
     *
     * @param color the ground, or null to leave the master's showing through
     */
    static void setBackground(XSLFSlide slide, Color color) {
        if (color == null) return;
        CTSlide ct = slide.getXmlObject();
        CTBackground bg = ct.getCSld().isSetBg()
                ? ct.getCSld().getBg() : ct.getCSld().addNewBg();
        CTBackgroundProperties props = bg.isSetBgPr() ? bg.getBgPr() : bg.addNewBgPr();
        CTSolidColorFillProperties fill = props.isSetSolidFill()
                ? props.getSolidFill() : props.addNewSolidFill();
        CTSRgbColor rgb = fill.isSetSrgbClr() ? fill.getSrgbClr() : fill.addNewSrgbClr();
        rgb.setVal(new byte[] {
                (byte) color.getRed(), (byte) color.getGreen(), (byte) color.getBlue() });
    }

    static LayoutMap.Signature signatureOf(Map<String, Object> card) {
        return new LayoutMap.Signature(
                card.get("title") != null,
                card.get("subtitle") != null,
                card.get("body") != null,
                card.get("aside") != null);
    }

    // ---- slide filling ----

    private static void fillSlide(XSLFSlide slide, Map<String, Object> card) {
        // The layout's furniture is not content. Leaving it produces a deck
        // with an empty date and page number stamped on every slide.
        for (XSLFShape s : slide.getShapes().toArray(new XSLFShape[0])) {
            if (s instanceof XSLFTextShape t && t.getTextType() != null) {
                switch (t.getTextType()) {
                    case DATETIME, FOOTER, SLIDE_NUMBER -> slide.removeShape(t);
                    default -> { }
                }
            }
        }

        XSLFTextShape titlePh = null;
        XSLFTextShape subtitlePh = null;
        List<XSLFTextShape> content = new ArrayList<>();
        for (XSLFShape s : slide.getShapes()) {
            if (!(s instanceof XSLFTextShape t) || t.getTextType() == null) continue;
            switch (t.getTextType()) {
                case TITLE, CENTERED_TITLE -> titlePh = t;
                case SUBTITLE -> subtitlePh = t;
                case BODY, CONTENT -> content.add(t);
                default -> { }
            }
        }

        fillLine(titlePh, asMap(card.get("title")));
        fillLine(subtitlePh, asMap(card.get("subtitle")));

        int next = 0;
        next = fillRegion(content, next, asMap(card.get("body")));
        next = fillRegion(content, next, asMap(card.get("aside")));
        // A region whose only content is a figure never claimed a placeholder
        // (see fillRegion), so the loop below removes the one it would have
        // used — the picture is placed separately, over the space it leaves.
        // An unfilled placeholder renders as PowerPoint's "Click to add text"
        // prompt in edit view, so drop the ones this card didn't use.
        for (int i = next; i < content.size(); i++) {
            slide.removeShape(content.get(i));
        }
    }

    private static void fillLine(XSLFTextShape ph, Map<String, Object> node) {
        if (ph == null || node == null) return;
        Map<String, Object> st = asMap(node.get("style"));
        prepare(ph, asMap(node.get("rect")));
        ph.clearText();
        XSLFTextParagraph p = ph.addNewTextParagraph();
        p.setTextAlign(align(str(st == null ? null : st.get("textAlign"))));
        styleRun(p.addNewTextRun(), str(node.get("text")), st);
    }

    /**
     * Fill one region's prose into the next content placeholder.
     *
     * <p>A region with no paragraphs — a card whose body is nothing but a
     * diagram, or a code fence — claims no placeholder at all. Claiming one and
     * leaving it empty would put PowerPoint's "Click to add text" prompt behind
     * the picture on every such slide.
     *
     * @return the next free content-placeholder index
     */
    private static int fillRegion(List<XSLFTextShape> content, int index, Map<String, Object> node) {
        if (node == null) return index;
        if (asList(node.get("paras")).isEmpty()) return index;
        if (index >= content.size()) {
            // LayoutMap is meant to make this unreachable; if it ever fires,
            // failing beats dropping the region on the floor.
            throw new IllegalStateException(
                    "No content placeholder left for a harvested region — "
                    + "LayoutMap and the chosen layout disagree.");
        }
        XSLFTextShape ph = content.get(index);
        prepare(ph, asMap(node.get("rect")));
        ph.clearText();
        for (Map<String, Object> para : asList(node.get("paras"))) {
            Map<String, Object> st = asMap(para.get("style"));
            boolean bullet = Boolean.TRUE.equals(para.get("bullet"));
            double fontPx = SlideHarvest.px(str(st == null ? null : st.get("fontSize")));
            double linePx = SlideHarvest.px(str(st == null ? null : st.get("lineHeight")));

            XSLFTextParagraph p = ph.addNewTextParagraph();
            p.setBullet(bullet);
            if (bullet) {
                // A bullet's hanging indent has to be derived from the type
                // size; a fixed value collapses onto the text at display sizes.
                p.setIndent(-fontPx * PT * 0.75);
                p.setLeftMargin(fontPx * PT * 0.95);
            } else {
                p.setIndent(0.0);
                p.setLeftMargin(0.0);
            }
            p.setTextAlign(align(str(st == null ? null : st.get("textAlign"))));
            if (fontPx > 0 && linePx > 0) p.setLineSpacing(linePx / fontPx * 100);

            for (Map<String, Object> run : asList(para.get("runs"))) {
                styleRun(p.addNewTextRun(), str(run.get("text")), asMap(run.get("style")));
            }
        }
        return index + 1;
    }

    private static void styleRun(XSLFTextRun run, String text, Map<String, Object> st) {
        String value = text == null ? "" : text;
        Map<String, Object> style = st == null ? Map.of() : st;
        // PowerPoint has no text-transform, so the cascade's decision has to be
        // baked into the characters or it is simply lost.
        run.setText("uppercase".equals(str(style.get("textTransform")))
                ? value.toUpperCase(Locale.ROOT) : value);
        run.setFontFamily(SlideHarvest.family(str(style.get("fontFamily"))));
        double size = SlideHarvest.px(str(style.get("fontSize")));
        if (size > 0) run.setFontSize(size * PT);
        run.setBold(SlideHarvest.weight(str(style.get("fontWeight"))) >= 600);
        run.setItalic("italic".equals(str(style.get("fontStyle"))));
        Color c = cssColor(str(style.get("color")));
        if (c != null) run.setFontColor(c);
        double tracking = SlideHarvest.px(str(style.get("letterSpacing")));
        if (tracking > 0) run.setCharacterSpacing(tracking * PT);
    }

    private static void prepare(XSLFTextShape ph, Map<String, Object> rect) {
        ph.setInsets(new Insets2D(0, 0, 0, 0));
        ph.setWordWrap(true);
        ph.setVerticalAlignment(VerticalAlignment.TOP);
        ph.setTextAutofit(XSLFTextShape.TextAutofit.NONE);
        if (rect == null) return;
        // +2/+4pt of slack: PowerPoint wraps marginally tighter than Chromium,
        // and a box sized to the exact measured rect drops its last line.
        forceAnchor(ph, new Rectangle(
                (int) Math.round(num(rect.get("x")) * PT),
                (int) Math.round(num(rect.get("y")) * PT),
                (int) Math.round(num(rect.get("w")) * PT) + 2,
                (int) Math.round(num(rect.get("h")) * PT) + 4));
    }

    /**
     * Set a placeholder's geometry.
     *
     * <p>POI 5.2.5 silently discards {@code setAnchor()} on a {@code TITLE}
     * placeholder — it persists for {@code BODY}/{@code CONTENT}, and
     * {@code getAnchor()} keeps reporting the value that was set, but the
     * written {@code <p:spPr/>} is empty and PowerPoint falls back to the
     * layout's own title position. Writing the transform bean directly
     * persists for every placeholder type.
     */
    static void forceAnchor(XSLFTextShape shape, Rectangle r) {
        CTShape ct = (CTShape) shape.getXmlObject();
        CTTransform2D xfrm = ct.getSpPr().isSetXfrm()
                ? ct.getSpPr().getXfrm() : ct.getSpPr().addNewXfrm();
        if (xfrm.getOff() == null) xfrm.addNewOff();
        if (xfrm.getExt() == null) xfrm.addNewExt();
        xfrm.getOff().setX((long) r.x * EMU);
        xfrm.getOff().setY((long) r.y * EMU);
        xfrm.getExt().setCx((long) r.width * EMU);
        xfrm.getExt().setCy((long) r.height * EMU);
    }

    private static void attachNotes(XMLSlideShow ppt, XSLFSlide slide, String text,
                                    Consumer<String> onWarn) {
        XSLFNotes notes = ppt.getNotesSlide(slide);
        for (XSLFTextShape sh : notes.getPlaceholders()) {
            if (sh.getTextType() == Placeholder.BODY) {
                sh.clearText();
                sh.addNewTextParagraph().addNewTextRun().setText(text);
                return;
            }
        }
        if (onWarn != null) {
            onWarn.accept("Notes master has no body placeholder;"
                    + " speaker notes were not written");
        }
    }

    /**
     * Place each of a card's figures as a picture at the rect it occupied.
     *
     * <p>Deduplication is POI's: {@code addPicture} keys on the image's
     * checksum, so one diagram repeated across slides is stored once.
     *
     * @return how many pictures were placed
     */
    private static int placeFigures(XMLSlideShow ppt, XSLFSlide slide, Map<String, Object> card,
                                    Map<String, byte[]> images, Consumer<String> onWarn) {
        int placed = 0;
        for (String key : new String[] {"body", "aside"}) {
            Map<String, Object> region = asMap(card.get(key));
            if (region == null) continue;
            for (Map<String, Object> fig : asList(region.get("figures"))) {
                String id = str(fig.get("id"));
                byte[] png = images == null ? null : images.get(id);
                if (png == null || png.length == 0) {
                    if (onWarn != null) {
                        onWarn.accept("Card '" + card.get("id") + "': no image captured for its "
                                + fig.get("kind") + " — that figure is missing from the slide");
                    }
                    continue;
                }
                Map<String, Object> rect = asMap(fig.get("rect"));
                if (rect == null) {
                    if (onWarn != null) {
                        onWarn.accept("Card '" + card.get("id") + "': figure " + id
                                + " has no geometry and was skipped");
                    }
                    continue;
                }
                XSLFPictureData data = ppt.addPicture(png, PictureData.PictureType.PNG);
                XSLFPictureShape pic = slide.createPicture(data);
                // No slack here, unlike a text box: a picture is scaled to its
                // frame, so padding the frame would stretch the image.
                pic.setAnchor(new Rectangle(
                        (int) Math.round(num(rect.get("x")) * PT),
                        (int) Math.round(num(rect.get("y")) * PT),
                        (int) Math.round(num(rect.get("w")) * PT),
                        (int) Math.round(num(rect.get("h")) * PT)));
                placed++;
            }
        }
        return placed;
    }

    /** Authored notes, then any unplaced leftovers, flagged so a presenter can tell. */
    static String notesFor(Map<String, Object> card) {
        String authored = str(card.get("notes"));
        String leftover = str(card.get("unplaced"));
        if (authored == null && leftover == null) return null;
        if (leftover == null) return authored;
        String tail = "[unplaced — not shown on the slide] " + leftover;
        return authored == null ? tail : authored + "\n\n" + tail;
    }

    // ---- small helpers ----

    static TextAlign align(String css) {
        if (css == null) return TextAlign.LEFT;
        return switch (css) {
            case "center" -> TextAlign.CENTER;
            case "right", "end" -> TextAlign.RIGHT;
            case "justify" -> TextAlign.JUSTIFY;
            default -> TextAlign.LEFT;
        };
    }

    static Color cssColor(String css) {
        if (css == null) return null;
        Matcher m = RGB.matcher(css);
        if (!m.find()) return null;
        // Fully transparent means "inherit whatever is behind"; setting black
        // would paint a ground the theme deliberately left open.
        if (m.group(4) != null && Double.parseDouble(m.group(4)) == 0) return null;
        return new Color(Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)));
    }

    static String ellipsis(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object o) {
        return o instanceof List ? (List<Map<String, Object>>) o : List.of();
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
