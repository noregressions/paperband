package dev.noregressions.paperband.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A layout's grid: named areas that boxes are placed in with {@code area:}.
 *
 * <pre>
 * grid:
 *   columns: [1fr, 2fr]               # track sizes: Nfr, auto, or a length (mm cm in pt px);
 *   rows: [auto, 1fr]                 #   default: 1fr per column, auto per row
 *   areas:                            # one string per row; "." leaves a cell empty
 *     - "title title"
 *     - "intro body"
 *   gap: 24px                         # optional; or "8px 24px" for rows then columns
 * </pre>
 *
 * Every named area must be a rectangle. On the web this becomes a CSS grid that stacks into one
 * column on narrow screens; PDF and PPTX will turn it into rectangles on the page.
 */
public record Grid(List<String> columns, List<String> rows, List<List<String>> areas, String gap, boolean rowsGiven) {

    public Grid(List<String> columns, List<String> rows, List<List<String>> areas, String gap) {
        this(columns, rows, areas, gap, true);
    }

    private static final Pattern TRACK = Pattern.compile("\\d+(\\.\\d+)?fr|auto|\\d+(\\.\\d+)?(mm|cm|in|pt|px)");
    private static final Pattern AREA = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*|\\.+");

    static Grid parse(YamlSpec spec) throws ConfigException {
        List<String> rowSpecs = spec.strings("areas");
        if (rowSpecs.isEmpty()) {
            throw new ConfigException(spec.context() + ": missing required key \"areas\"");
        }
        List<List<String>> areas = new ArrayList<>();
        for (String row : rowSpecs) {
            List<String> cells = Arrays.asList(row.strip().split("\\s+"));
            for (String cell : cells) {
                if (!AREA.matcher(cell).matches()) {
                    throw spec.error("areas", "\"" + cell + "\" isn't an area name (letters, digits, - and _, starting"
                            + " with a letter) or \".\" for an empty cell");
                }
            }
            if (!areas.isEmpty() && cells.size() != areas.getFirst().size()) {
                throw spec.error("areas", "every row needs the same number of cells; \"" + rowSpecs.getFirst()
                        + "\" has " + areas.getFirst().size() + " but \"" + row + "\" has " + cells.size());
            }
            areas.add(List.copyOf(cells));
        }
        int width = areas.getFirst().size();
        List<String> columns = tracks(spec, "columns", width, "1fr");
        boolean rowsGiven = spec.has("rows");
        List<String> rows = tracks(spec, "rows", areas.size(), "auto");
        String gap = spec.string("gap", null);
        if (gap != null) {
            String[] parts = gap.strip().split("\\s+");
            if (parts.length > 2) {
                throw spec.error("gap", "expected one length, or two (rows then columns), got \"" + gap + "\"");
            }
            for (String part : parts) {
                PageSpec.length(part, spec.child("gap"));
            }
        }
        spec.finish();
        Grid grid = new Grid(columns, rows, List.copyOf(areas), gap, rowsGiven);
        grid.checkRectangles(spec);
        return grid;
    }

    private static List<String> tracks(YamlSpec spec, String key, int count, String fallback) throws ConfigException {
        List<String> tracks = spec.strings(key);
        if (tracks.isEmpty()) {
            return java.util.Collections.nCopies(count, fallback);
        }
        if (tracks.size() != count) {
            throw spec.error(key, "has " + tracks.size() + " size(s) but the areas have " + count);
        }
        for (String t : tracks) {
            if (!TRACK.matcher(t).matches()) {
                throw spec.error(key, "\"" + t + "\" isn't a track size; use Nfr, auto, or a length (mm, cm, in, pt, px)");
            }
        }
        return List.copyOf(tracks);
    }

    private void checkRectangles(YamlSpec spec) throws ConfigException {
        Map<String, int[]> bounds = new LinkedHashMap<>(); // name -> {minRow, minCol, maxRow, maxCol, cells}
        for (int r = 0; r < areas.size(); r++) {
            for (int c = 0; c < areas.get(r).size(); c++) {
                String name = areas.get(r).get(c);
                if (name.startsWith(".")) {
                    continue;
                }
                int[] b = bounds.computeIfAbsent(name, k -> new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1, 0});
                b[0] = Math.min(b[0], r);
                b[1] = Math.min(b[1], c);
                b[2] = Math.max(b[2], r);
                b[3] = Math.max(b[3], c);
                b[4]++;
            }
        }
        for (var e : bounds.entrySet()) {
            int[] b = e.getValue();
            if ((b[2] - b[0] + 1) * (b[3] - b[1] + 1) != b[4]) {
                throw spec.error("areas", "area \"" + e.getKey() + "\" isn't a rectangle");
            }
        }
    }

    /** The named areas in each row, top to bottom. */
    List<java.util.Set<String>> rowAreas() {
        List<java.util.Set<String>> out = new ArrayList<>();
        for (List<String> row : areas) {
            java.util.Set<String> names = new LinkedHashSet<>();
            row.stream().filter(c -> !c.startsWith(".")).forEach(names::add);
            out.add(names);
        }
        return out;
    }

    /** The named areas, in order of first appearance. */
    public Set<String> names() {
        Set<String> names = new LinkedHashSet<>();
        for (List<String> row : areas) {
            for (String cell : row) {
                if (!cell.startsWith(".")) {
                    names.add(cell);
                }
            }
        }
        return names;
    }

    /** CSS declarations for the grid container. */
    String css() {
        return css(rows);
    }

    /** CSS declarations for the grid container, with these row sizes. */
    String css(List<String> rowSizes) {
        StringBuilder sb = new StringBuilder("display: grid; grid-template-columns: ").append(String.join(" ", columns))
                .append("; grid-template-rows: ").append(String.join(" ", rowSizes)).append("; grid-template-areas:");
        for (List<String> row : areas) {
            sb.append(" \"").append(String.join(" ", row)).append('"');
        }
        sb.append(';');
        if (gap != null) {
            sb.append(" gap: ").append(gap).append(';');
        }
        return sb.toString();
    }
}
