package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.SiteBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Themes: the built-in one, and a directory that changes it. */
class ThemeTest {

    @TempDir
    Path dir;

    private void write(String file, String text) throws IOException {
        Path p = dir.resolve(file);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    private SiteBuilder.Build build(String manifest) throws IOException {
        write("manifest.yml", manifest);
        return SiteBuilder.build(Manifest.load(dir.resolve("manifest.yml")));
    }

    @Test
    void theBuiltInThemeHasEveryPart() {
        for (Theme.Part part : Theme.Part.values()) {
            assertFalse(Theme.DEFAULT.css(part).isBlank(), part.file());
        }
        assertEquals("", Theme.DEFAULT.extra());
        assertNull(Theme.DEFAULT.layouts());
    }

    @Test
    void withoutAThemeTheBuiltInOneIsUsed() throws IOException {
        write("a.md", "# A\n");
        String html = build("files: [a.md]\n").html();
        assertTrue(html.contains(Theme.DEFAULT.css(Theme.Part.WEB)), html);
        assertTrue(html.contains(Theme.DEFAULT.css(Theme.Part.CONTENT)), html);
        assertFalse(html.contains("--pb-desk-bg"), "the paged part isn't in web output");
    }

    @Test
    void themeCssComesAfterThePartsAndBeforeTheStylesheets() throws IOException {
        write("a.md", "# A\n");
        write("themes/mine/theme.css", ":root { --pb-font-body: Georgia, serif; }");
        write("site.css", "h1 { color: teal; }\n");
        String html = build("theme: themes/mine\nstylesheets: [site.css]\nfiles: [a.md]\n").html();
        int parts = html.indexOf("--pb-font-body: system-ui");
        int extra = html.indexOf("--pb-font-body: Georgia");
        int sheet = html.indexOf("h1 { color: teal; }");
        assertTrue(parts >= 0 && parts < extra && extra < sheet, html);
    }

    @Test
    void aPartReplacesTheBuiltInOneAndTheOthersStay() throws IOException {
        write("a.md", "# A\n");
        write("themes/mine/web.css", "body { font: 18px/1.5 serif; }\n");
        String html = build("theme: themes/mine\nfiles: [a.md]\n").html();
        assertTrue(html.contains("body { font: 18px/1.5 serif; }"), html);
        assertFalse(html.contains("--pb-page-width: 46rem"), "the built-in web part is gone: " + html);
        assertTrue(html.contains(Theme.DEFAULT.css(Theme.Part.CONTENT)), "the content part is still the built-in one");
    }

    @Test
    void pagedOutputUsesThePagedPart() throws IOException {
        write("a.md", "# A\n");
        write("themes/mine/paged.css", "body { color: navy; }\n");
        write("themes/mine/web.css", "body { color: maroon; }\n");
        String html = build("""
                theme: themes/mine
                sources: [a.md]
                sequence: [{ file: a.md }]
                outputs: [{ name: book, format: paged-html, file: book.html }]
                """).html();
        assertTrue(html.contains("body { color: navy; }") && !html.contains("maroon"), html);
        assertTrue(html.contains("@font-face"), "the bundled fonts are still declared");
    }

    @Test
    void aSiteTakesItsOwnStylesFromTheTheme() throws IOException {
        write("a.md", "# A\n");
        write("b.md", "# B\n");
        write("themes/mine/site.css", ".site-header { color: teal; }\n");
        write("themes/mine/nav-side.css", "nav.site-nav { color: olive; }\n");
        Map<Path, String> site = build("theme: themes/mine\nfiles: [a.md, b.md]\n").outputs().getFirst().site();
        String css = site.get(Path.of(WebSite.CSS));
        assertTrue(css.startsWith(".site-header { color: teal; }"), css);
        assertTrue(css.contains("nav.site-nav { color: olive; }"), css);
        assertFalse(css.contains(".site-pager"), "the built-in site part is gone: " + css);
    }

    @Test
    void aThemesLayoutsAreAvailableAndTheProjectsReplaceThem() throws IOException {
        write("a.md", "---\nlayout: card\n---\n# A\n");
        write("b.md", "---\nlayout: plain\n---\n# B\n");
        write("themes/mine/layouts/card.yml", "class: from-theme\nboxes: [{ id: all, select: rest }]\n");
        write("themes/mine/layouts/plain.yml", "class: from-theme\nboxes: [{ id: all, select: rest }]\n");
        write("layouts/plain.yml", "class: from-project\nboxes: [{ id: all, select: rest }]\n");
        String html = build("theme: themes/mine\nfiles: [a.md, b.md]\n").html();
        assertTrue(html.contains("layout-card") && html.contains("from-theme"), html);
        assertTrue(html.contains("from-project"), html);
        assertEquals(1, html.split("from-theme", -1).length - 1, "the project's plain replaced the theme's");
    }

    @Test
    void filesOfTheThemeItsStylesheetsReferToAreInlined() throws IOException {
        write("a.md", "# A\n");
        Files.createDirectories(dir.resolve("themes/mine/fonts"));
        Files.write(dir.resolve("themes/mine/fonts/text.woff2"), new byte[] {1, 2, 3});
        write("themes/mine/theme.css", """
                @font-face { font-family: "Mine"; src: url("fonts/text.woff2") format("woff2"); }
                .a { background: url(fonts/missing.png); }
                .b { background: url("https://example.com/b.png"); }
                .c { background: url(../../a.md); }
                """);
        String html = build("theme: themes/mine\nfiles: [a.md]\n").html();
        assertTrue(html.contains("src: url(\"data:font/woff2;base64,AQID\") format(\"woff2\")"), html);
        assertTrue(html.contains("url(fonts/missing.png)"), "a file the theme doesn't have is left as written");
        assertTrue(html.contains("url(\"https://example.com/b.png\")"), "so is a URL elsewhere");
        assertTrue(html.contains("url(../../a.md)"), "and a file outside the theme");
    }

    @Test
    void aMissingThemeDirectoryIsAnError() throws IOException {
        write("a.md", "# A\n");
        ConfigException e = assertThrows(ConfigException.class, () -> build("theme: themes/none\nfiles: [a.md]\n"));
        assertTrue(e.getMessage().contains("neither a directory") && e.getMessage().contains("[default, forest]"),
                e.getMessage());
    }

    @Test
    void aThemePaperbandBringsIsNamed() throws IOException {
        write("a.md", "---\nlayout: section\n---\nNext {! label}\n\n# A\n");
        String html = build("theme: forest\nfiles: [a.md]\n").html();
        assertTrue(html.contains("--forest-green: #2F5D50"), "its stylesheet");
        assertTrue(html.contains("font/woff2;base64,"), "its fonts, inlined");
        assertTrue(html.contains("layout-section"), "its layouts");
        assertEquals(java.util.List.of("default", "forest"), Theme.names());
    }

    @Test
    void aDirectoryOfTheSameNameWinsOverTheThemePaperbandBrings() throws IOException {
        write("a.md", "# A\n");
        write("forest/theme.css", ".mine { color: teal; }\n");
        String html = build("theme: forest\nfiles: [a.md]\n").html();
        assertTrue(html.contains(".mine { color: teal; }") && !html.contains("--forest-green"), html);
    }

    @Test
    void aStylesheetThatIsNotAPartIsAnError() throws IOException {
        write("a.md", "# A\n");
        write("themes/mine/webb.css", "body { }\n");
        ConfigException e = assertThrows(ConfigException.class, () -> build("theme: themes/mine\nfiles: [a.md]\n"));
        assertTrue(e.getMessage().contains("webb.css isn't a part of a theme") && e.getMessage().contains("web.css"),
                e.getMessage());
    }
}
