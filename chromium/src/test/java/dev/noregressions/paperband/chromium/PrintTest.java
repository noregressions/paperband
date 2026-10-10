package dev.noregressions.paperband.chromium;

import dev.noregressions.paperband.config.BuildConfig;
import dev.noregressions.paperband.config.Runner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** How flowing pages print: margins on every sheet, and page numbers from a stylesheet. */
class PrintTest {

    @TempDir
    Path dir;

    private void write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void flowingPagesHaveMarginsOnEverySheetAndStylesheetsCanNumberThem() throws IOException {
        write("docs/cover.md", "# Cover\n");
        write("docs/long.md", "# Long\n\n" + "A sentence that takes up some room on the page. ".repeat(400) + "\n");
        write("layouts/cover.yml", "page: { size: A5, margin: 0mm }\nstack: {}\nboxes: [{ id: all, select: rest }]\n");
        write("styles/print.css", "@page { @bottom-center { content: \"Page \" counter(page); } }\n");
        write("manifest.yml", """
                sources: [docs/*.md]
                stylesheets: [styles/print.css]
                sequence: [{ file: docs/cover.md, layout: cover }, { file: docs/long.md }]
                outputs: [{ name: print, format: pdf, file: out/a.pdf, page: { size: A5, margin: 15mm } }]
                """);
        BuildConfig.Builder b = BuildConfig.builder(dir).manifest(dir.resolve("manifest.yml"));
        if (ChromiumBrowserTest.BROWSER != null) {
            b.browser(ChromiumBrowserTest.BROWSER);
        }
        Runner.run(b.build());
        byte[] pdf = Files.readAllBytes(dir.resolve("out/a.pdf"));
        int sheets = ChromiumBrowserTest.pages(pdf).size();
        assertTrue(sheets >= 4, "the long page runs onto several sheets: " + sheets);
        Path out = dir.resolve("out/a.pdf");
        assertFalse(PdfText.sheet(out, 1).contains("Page 1"), "the cover is a fixed page, with no margin to number it in");
        for (int n = 2; n <= sheets; n++) {
            assertTrue(PdfText.sheet(out, n).contains("Page " + n), "sheet " + n + " is numbered: " + PdfText.sheet(out, n));
            assertTrue(PdfText.firstTextTop(out, n) >= 14, "sheet " + n + " keeps its top margin: "
                    + PdfText.firstTextTop(out, n) + "mm");
            // Nothing scaled to fit: the text starts at the margin, not further in.
            assertEquals(15, PdfText.firstTextLeft(out, n), 1, "sheet " + n + " starts at its 15mm left margin");
        }
    }

    @Test
    void aLayoutCanPrintItsPagesLandscapeAmongPortraitOnes() throws IOException {
        write("docs/a.md", "# Portrait\n\nText.\n");
        write("docs/wide.md", "# Wide\n\n" + "A sentence that takes up some room on the page. ".repeat(300) + "\n");
        write("docs/c.md", "# Portrait again\n\nText.\n");
        write("layouts/landscape.yml", "page: { size: A4, orientation: landscape, margin: 15mm }\nboxes: [{ id: body, select: rest }]\n");
        write("manifest.yml", """
                sources: [docs/*.md]
                sequence: [{ file: docs/a.md }, { file: docs/wide.md, layout: landscape }, { file: docs/c.md }]
                outputs: [{ name: print, format: pdf, file: out/a.pdf, page: { size: A4, margin: 20mm } }]
                """);
        BuildConfig.Builder b = BuildConfig.builder(dir).manifest(dir.resolve("manifest.yml"));
        if (ChromiumBrowserTest.BROWSER != null) {
            b.browser(ChromiumBrowserTest.BROWSER);
        }
        Runner.run(b.build());
        java.util.List<double[]> sheets = ChromiumBrowserTest.pages(Files.readAllBytes(dir.resolve("out/a.pdf")));
        assertTrue(sheets.size() >= 4, "the landscape page flows onto more than one sheet: " + sheets.size());
        assertTrue(sheets.getFirst()[0] < sheets.getFirst()[1], "the first page is portrait");
        for (int n = 1; n < sheets.size() - 1; n++) {
            assertTrue(sheets.get(n)[0] > sheets.get(n)[1], "sheet " + (n + 1) + " is landscape");
        }
        assertTrue(sheets.getLast()[0] < sheets.getLast()[1], "the last page is portrait again");
        assertTrue(PdfText.sheet(dir.resolve("out/a.pdf"), 2).contains("Wide"));
        assertEquals(15, PdfText.firstTextLeft(dir.resolve("out/a.pdf"), 3), 1, "landscape sheets keep their own 15mm margin");
    }
}
