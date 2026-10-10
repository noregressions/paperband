package dev.noregressions.paperband.chromium;

import dev.noregressions.paperband.config.BuildConfig;
import dev.noregressions.paperband.config.Runner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Page numbers for {@code pageref} links, filled in over several prints: an index, here. */
class PageRefsTest {

    @TempDir
    Path dir;

    private void write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static final String FILLER = "A sentence that takes up some room on the page. ".repeat(120);

    private Path build() throws IOException {
        BuildConfig.Builder b = BuildConfig.builder(dir).manifest(dir.resolve("manifest.yml"));
        if (ChromiumBrowserTest.BROWSER != null) {
            b.browser(ChromiumBrowserTest.BROWSER);
        }
        Runner.run(b.build());
        return dir.resolve("out/a.pdf");
    }

    private static int sheetWith(Path pdf, String text, int sheets) throws IOException {
        for (int n = 1; n <= sheets; n++) {
            if (PdfText.sheet(pdf, n).contains(text)) {
                return n;
            }
        }
        return fail("no sheet has \"" + text + "\"");
    }

    @Test
    void indexEntriesShowThePagesTheirMarkersLandOn() throws IOException {
        write("docs/a.md", "# Chapter\n\nAlphaword here. {! index=Alpha}\n\nBetaword one. {! index=Beta}\n\n"
                + "Betaword two, same page. {! index=Beta}\n\n" + FILLER + "\n\n" + FILLER + "\n\n"
                + "Omegaword at the end. {! index=\"Omega; Beta\"}\n");
        write("docs/index.md", """
                # Index

                [% for t in site.index.terms %]
                - [[ t.term ]] [% for at in t.locators %][% set link = "[x](#" ~ at.anchor ~ ")" %][[ link ]]{! pageref}[% endfor %]
                [% endfor %]
                """);
        write("styles/index.css", ".pageref ~ .pageref::before { content: \", \"; }\n");
        write("manifest.yml", """
                sources: [docs/*.md]
                stylesheets: [styles/index.css]
                sequence: [{ file: docs/a.md }, { file: docs/index.md }]
                outputs: [{ name: print, format: pdf, file: out/a.pdf, page: { size: A5, margin: 15mm } }]
                """);
        Path pdf = build();
        int sheets = ChromiumBrowserTest.pages(Files.readAllBytes(pdf)).size();
        int alpha = sheetWith(pdf, "Alphaword", sheets);
        int omega = sheetWith(pdf, "Omegaword", sheets);
        assertTrue(omega > alpha, "the filler pushes Omega onto a later sheet");

        String index = PdfText.sheet(pdf, sheetWith(pdf, "Index", sheets));
        assertTrue(index.contains("Alpha " + alpha), index);
        assertTrue(index.contains("Omega " + omega), index);
        assertTrue(index.contains("Beta " + alpha + ", " + omega), "the second Beta on the same sheet is hidden: " + index);
        assertFalse(index.matches("(?s).*\\bx\\b.*"), "every link got its number: " + index);
    }

    @Test
    void aReferenceToAMissingIdFailsTheBuild() throws IOException {
        write("docs/a.md", "# A\n\nSee [x](#nowhere){! pageref}.\n");
        write("manifest.yml", """
                sources: [docs/*.md]
                sequence: [{ file: docs/a.md }]
                outputs: [{ name: print, format: pdf, file: out/a.pdf }]
                """);
        Exception e = assertThrows(Exception.class, this::build);
        String messages = e + " " + e.getCause();
        assertTrue(messages.contains("#nowhere"), messages);
    }
}
