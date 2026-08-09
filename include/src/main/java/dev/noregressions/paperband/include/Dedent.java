package dev.noregressions.paperband.include;

import java.util.List;

/**
 * Strip the common leading-whitespace prefix from a list of lines. Skips blank
 * lines when computing the minimum so a captured snippet that contains a stray
 * empty line doesn't collapse the dedent to zero.
 */
final class Dedent {

    private Dedent() {}

    /**
     * @return the lines joined with {@code \n}, with the common leading
     *         whitespace stripped. Empty input returns the empty string.
     */
    static String dedent(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";

        int min = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.isBlank()) continue;
            int prefix = 0;
            while (prefix < line.length() && Character.isWhitespace(line.charAt(prefix))) {
                prefix++;
            }
            if (prefix < min) min = prefix;
        }
        if (min == Integer.MAX_VALUE) min = 0;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.length() >= min && !line.isBlank()) {
                sb.append(line, min, line.length());
            } else if (line.isBlank()) {
                // Preserve blank lines without trying to clip non-existent whitespace.
                sb.append(line.trim());
            } else {
                sb.append(line);
            }
            if (i < lines.size() - 1) sb.append('\n');
        }
        return sb.toString();
    }
}
