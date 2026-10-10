package dev.noregressions.paperband;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The fonts paged outputs use, bundled so pages measure and print the same everywhere: Inter for
 * text and JetBrains Mono for code, both under the SIL Open Font License (the licences are
 * bundled with them). Paged HTML refers to them as {@value #DIR}{@code /<file>}: next to a
 * paged-html file they're copied there ({@link #copyTo}); in the browser they're served from here.
 */
public final class Fonts {

    /** The directory, relative to a paged document, its fonts are loaded from. */
    public static final String DIR = "paperband-fonts";

    /** The text and code font families. */
    public static final String SANS = "Inter";
    public static final String MONO = "JetBrains Mono";

    private static final String LATIN = "U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,"
            + "U+0308,U+0329,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD";
    private static final String LATIN_EXT = "U+0100-02BA,U+02BD-02C5,U+02C7-02CC,U+02CE-02D7,U+02DD-02FF,U+0304,"
            + "U+0308,U+0329,U+1D00-1DBF,U+1E00-1E9F,U+1EF2-1EFF,U+2020,U+20A0-20AB,U+20AD-20C0,U+2113,U+2C60-2C7F,"
            + "U+A720-A7FF";

    private record Face(String family, String file, int weight, String style) {
    }

    private static final List<Face> FACES = faces();

    private static final List<String> LICENCES = List.of("Inter-OFL.txt", "JetBrainsMono-OFL.txt");

    private Fonts() {
    }

    private static List<Face> faces() {
        List<Face> out = new ArrayList<>();
        for (String style : List.of("normal", "italic")) {
            for (int weight : List.of(400, 700)) {
                out.add(new Face(SANS, "inter", weight, style));
            }
        }
        for (int weight : List.of(400, 700)) {
            out.add(new Face(MONO, "jetbrains-mono", weight, "normal"));
        }
        return List.copyOf(out);
    }

    /** Every bundled file (fonts and licences), by name. */
    public static List<String> files() {
        List<String> out = new ArrayList<>();
        for (Face f : FACES) {
            out.add(fileName(f, "latin"));
            out.add(fileName(f, "latin-ext"));
        }
        out.addAll(LICENCES);
        return out;
    }

    private static String fileName(Face f, String subset) {
        return f.file() + "-" + subset + "-" + f.weight() + "-" + f.style() + ".woff2";
    }

    /** The {@code @font-face} rules, loading from {@value #DIR}. */
    public static String css() {
        StringBuilder sb = new StringBuilder();
        for (Face f : FACES) {
            for (String subset : List.of("latin-ext", "latin")) {
                sb.append("@font-face { font-family: \"").append(f.family()).append("\"; font-style: ").append(f.style())
                        .append("; font-weight: ").append(f.weight()).append("; font-display: block; src: url(\"")
                        .append(DIR).append('/').append(fileName(f, subset)).append("\") format(\"woff2\"); unicode-range: ")
                        .append(subset.equals("latin") ? LATIN : LATIN_EXT).append("; }\n");
            }
        }
        return sb.toString();
    }

    /** A bundled file's bytes, or null if there's no such file. */
    public static byte[] read(String name) {
        if (!files().contains(name)) {
            return null;
        }
        try (InputStream in = Fonts.class.getResourceAsStream("fonts/" + name)) {
            if (in == null) {
                throw new IllegalStateException("bundled font missing: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes the fonts and their licences to {@code dir}/{@value #DIR}. */
    public static void copyTo(Path dir) throws IOException {
        Path target = dir.resolve(DIR);
        Files.createDirectories(target);
        for (String name : files()) {
            Files.write(target.resolve(name), read(name));
        }
    }
}
