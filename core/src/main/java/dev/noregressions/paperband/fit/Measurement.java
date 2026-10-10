package dev.noregressions.paperband.fit;

import java.util.List;

/**
 * What a browser measured of a paged document, in millimetres. Kept with the output, so later
 * renderers (PPTX) can place shapes exactly where the PDF has them.
 *
 * @param pages        every {@code section.page}, in order
 * @param brokenImages images that didn't load
 * @param failedFonts  fonts that didn't load, e.g. {@code Inter 700 italic}
 */
public record Measurement(List<Sheet> pages, List<BrokenImage> brokenImages, List<String> failedFonts) {

    /**
     * One page's sheet.
     *
     * @param id        the page's id
     * @param width     the sheet's size
     * @param margin    its padding (the page margin): top, right, bottom, left
     * @param overflowX how far its content reaches past its right edge
     * @param overflowY how far its content reaches past its bottom edge
     * @param boxes     every box with a {@code data-box} path, outer boxes before the boxes inside them
     */
    public record Sheet(String id, double width, double height, double[] margin, double overflowX, double overflowY,
                        List<Box> boxes) {
    }

    /**
     * One box, relative to its sheet's top-left corner.
     *
     * @param path      its {@code data-box} path, e.g. {@code steps[1]/command}
     * @param overflowX how much wider its content is than the box (long code lines, wide tables)
     * @param overflowY how much taller its content is than the box
     * @param units     for a box that splits, where each unit it shows starts, from the box's top
     * @param content   the height of everything in it, shown or not
     */
    public record Box(String path, double x, double y, double w, double h, double overflowX, double overflowY,
                      List<Double> units, double content) {

        public Box(String path, double x, double y, double w, double h, double overflowX, double overflowY) {
            this(path, x, y, w, h, overflowX, overflowY, List.of(), h + overflowY);
        }
    }

    /** An image that didn't load: where it is and what it pointed at. */
    public record BrokenImage(String page, String box, String src) {
    }
}
