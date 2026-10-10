package dev.noregressions.paperband.chromium;

import dev.noregressions.paperband.SiteBuilder;
import dev.noregressions.paperband.browser.Browser;
import dev.noregressions.paperband.browser.BrowserProvider;
import dev.noregressions.paperband.config.BuildConfig;
import dev.noregressions.paperband.config.Runner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prints in a real browser: Playwright's Chromium, or {@code -Dpaperband.browser=chrome} (or a path)
 * for an installed one.
 */
class ChromiumBrowserTest {

    static final String BROWSER = System.getProperty("paperband.browser");

    static Browser browser;

    @TempDir
    Path dir;

    @BeforeAll
    static void open() throws IOException {
        browser = new ChromiumProvider().open(BROWSER);
    }

    @AfterAll
    static void close() throws IOException {
        browser.close();
    }

    /** Each page's MediaBox, in points, as [width, height]. */
    static List<double[]> pages(byte[] pdf) {
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("/MediaBox\\s*\\[\\s*([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)\\s+([\\d.]+)\\s*]").matcher(text);
        List<double[]> out = new ArrayList<>();
        while (m.find()) {
            out.add(new double[]{Double.parseDouble(m.group(3)) - Double.parseDouble(m.group(1)),
                    Double.parseDouble(m.group(4)) - Double.parseDouble(m.group(2))});
        }
        return out;
    }

    static double mm(double points) {
        return points * 25.4 / 72;
    }

    @Test
    void theProviderIsFoundOnTheClasspath() {
        assertInstanceOf(ChromiumProvider.class, BrowserProvider.find().orElseThrow());
    }

    @Test
    void printsEachSheetAtItsPageSize() throws IOException {
        String html = """
                <!doctype html><html><head><style>
                @page a5 { size: 148mm 210mm; margin: 0 }
                @page wide { size: 297mm 167mm; margin: 0 }
                body { margin: 0 }
                .sheet { break-after: page; overflow: hidden }
                </style></head><body>
                <section class="sheet" style="page: a5; width: 148mm; height: 210mm">One</section>
                <section class="sheet" style="page: wide; width: 297mm; height: 167mm">Two</section>
                </body></html>
                """;
        byte[] pdf = browser.pdf(html, dir);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.ISO_8859_1));
        List<double[]> pages = pages(pdf);
        assertEquals(2, pages.size());
        assertEquals(148, mm(pages.get(0)[0]), 0.5);
        assertEquals(210, mm(pages.get(0)[1]), 0.5);
        assertEquals(297, mm(pages.get(1)[0]), 0.5);
        assertEquals(167, mm(pages.get(1)[1]), 0.5);
    }

    @Test
    void buildsPdfOutputsWithTheBundledFontsAndLocalImages() throws IOException {
        // A 1x1 PNG, linked relative to the output's directory, as the paged HTML would be.
        Files.createDirectories(dir.resolve("out/img"));
        Files.write(dir.resolve("out/img/dot.png"), Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="));
        Files.createDirectories(dir.resolve("docs"));
        Files.writeString(dir.resolve("docs/a.md"), "# Printed\n\nSome text with `code`.\n\n![dot](img/dot.png)\n");
        Files.writeString(dir.resolve("manifest.yml"), """
                sources: [docs/a.md]
                sequence: [{ file: docs/a.md }]
                outputs:
                  - { name: print, format: pdf, file: out/a.pdf, page: { size: A5, margin: 10mm } }
                """);
        BuildConfig.Builder config = BuildConfig.builder(dir).manifest(dir.resolve("manifest.yml"));
        if (BROWSER != null) {
            config.browser(BROWSER);
        }
        Runner.Outcome outcome = Runner.run(config.build());

        SiteBuilder.Output out = outcome.build().outputs().getFirst();
        byte[] pdf = Files.readAllBytes(dir.resolve("out/a.pdf"));
        assertArrayEquals(out.pdf(), pdf);
        List<double[]> pages = pages(pdf);
        assertEquals(1, pages.size());
        assertEquals(148, mm(pages.getFirst()[0]), 0.5);
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("Inter"), "the text font is embedded");
        assertTrue(text.contains("JetBrainsMono"), "the code font is embedded");
        assertTrue(text.contains("/Subtype /Image"), "the image loaded");
        assertFalse(Files.exists(dir.resolve("out/paperband-fonts")), "a pdf output needs no font files beside it");
    }

    @Test
    void copyButtonsAppearOnTheNamedLanguagesOnly() throws IOException {
        String html = "<!doctype html><html><body><pre><code class=\"language-command\">mvn package\n</code></pre>"
                + "<pre><code class=\"language-output\">BUILD SUCCESS\n</code></pre>"
                + dev.noregressions.paperband.layout.SiteHtmlRenderer.copyButtonsForTest(List.of("command")) + "</body></html>";
        Object r = ScriptPages.evaluate("copy-test-" + System.nanoTime(), html, """
                () => [...document.querySelectorAll("pre")].map(p => p.querySelector(".copy-button")?.textContent ?? "none")""", null);
        assertEquals(List.of("Copy", "none"), r);
    }
}
