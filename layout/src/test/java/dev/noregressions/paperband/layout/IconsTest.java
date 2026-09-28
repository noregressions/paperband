package dev.noregressions.paperband.layout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName(":name: icon references")
class IconsTest {

    private final Icons bundled = new Icons(null);

    @Test
    @DisplayName("a bundled name becomes an inline svg carrying its name as a class")
    void bundledNameResolves() {
        String out = bundled.apply("<p>:users: Reach</p>");
        assertTrue(out.startsWith("<p><svg class=\"icon icon-users\""), out);
        assertTrue(out.contains("stroke=\"currentColor\""), out);
        assertTrue(out.endsWith("</svg> Reach</p>"), out);
    }

    @Test
    @DisplayName("works inside hand-written table markup, where markdown never looks")
    void insideRawHtmlTable() {
        String out = bundled.apply("<table><tr><td class=\"x\">:trophy: Authority</td></tr></table>");
        assertTrue(out.contains("<td class=\"x\"><svg class=\"icon icon-trophy\""), out);
    }

    @Test
    @DisplayName("code, pre, script, style, head and existing svg are left alone")
    void opaqueElementsUntouched() {
        String html = "<head><title>:users:</title><style>a:hover{}</style></head>"
                + "<p><code>:users:</code></p><pre><code>:users:\n</code></pre>"
                + "<script>var a = ':users:';</script><svg><text>:users:</text></svg>";
        assertEquals(html, bundled.apply(html));
    }

    @Test
    @DisplayName("a stylesheet or script that mentions markup doesn't hide the rest of the page")
    void rawTextMentioningTags() {
        String out = bundled.apply("<style>/* the class goes on <pre> and <code> */</style>"
                + "<script>var s = '<svg>';</script><p>:users:</p>");
        assertTrue(out.contains("<p><svg class=\"icon icon-users\""), out);
    }

    @Test
    @DisplayName("an element classed no-icons keeps its text literal, nested tags included")
    void noIconsClass() {
        String html = "<p class=\"x no-icons\">:users: <span>:trophy:</span> :globe:</p><p>:users:</p>";
        String out = bundled.apply(html);
        assertTrue(out.startsWith("<p class=\"x no-icons\">:users: <span>:trophy:</span> :globe:</p>"), out);
        assertTrue(out.contains("<p><svg class=\"icon icon-users\""), out);
    }

    @Test
    @DisplayName("attribute values and comments are left alone")
    void attributesAndCommentsUntouched() {
        String html = "<a title=\"see :users: here\" href=\"x\">text</a><!-- :users: -->";
        assertEquals(html, bundled.apply(html));
    }

    @Test
    @DisplayName("colons touching words or other colons are not references")
    void boundaries() {
        for (String text : new String[] {
                "Foo::bar: baz",
                "dev.example:my-plugin:build",
                "at 10:30:45 today",
                "a :users:globe: chain",
                "key:users: value",
        }) {
            assertEquals("<p>" + text + "</p>", bundled.apply("<p>" + text + "</p>"), text);
        }
    }

    @Test
    @DisplayName("punctuation around a reference is fine")
    void punctuationBoundaries() {
        String out = bundled.apply("<p>(:users:), :trophy:.</p>");
        assertTrue(out.startsWith("<p>(<svg class=\"icon icon-users\""), out);
        assertTrue(out.contains("</svg>), <svg class=\"icon icon-trophy\""), out);
        assertTrue(out.endsWith("</svg>.</p>"), out);
    }

    @Test
    @DisplayName("::name: is the escape for a literal :name:")
    void escape() {
        assertEquals("<p>write :users: for an icon</p>",
                bundled.apply("<p>write ::users: for an icon</p>"));
    }

    @Test
    @DisplayName("an unknown name fails, naming it and suggesting the near miss")
    void unknownFails() {
        LayoutException e = assertThrows(LayoutException.class,
                () -> bundled.apply("<p>the :userz: column</p>"));
        assertTrue(e.getMessage().contains(":userz:"), e.getMessage());
        assertTrue(e.getMessage().contains("'users'"), e.getMessage());
        assertTrue(e.getMessage().contains("the :userz: column"), e.getMessage());
    }

    @Test
    @DisplayName("a book's icons/ adds names and replaces bundled drawings")
    void bookIconsWin(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("logo.svg"),
                "<?xml version=\"1.0\"?>\n<!-- made by hand -->\n"
                        + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"48\" height=\"48\" "
                        + "viewBox=\"0 0 48 48\"><circle cx=\"24\" cy=\"24\" r=\"20\"/></svg>");
        Files.writeString(dir.resolve("users.svg"),
                "<svg viewBox=\"0 0 10 10\"><rect width=\"10\" height=\"10\"/></svg>");
        Icons icons = new Icons(dir);

        String logo = icons.apply("<p>:logo:</p>");
        assertTrue(logo.startsWith("<p><svg class=\"icon icon-logo\" width=\"1em\" height=\"1em\""), logo);
        assertTrue(logo.contains("viewBox=\"0 0 48 48\""), logo);
        assertFalse(logo.contains("width=\"48\""), logo);
        assertFalse(logo.contains("<?xml") || logo.contains("made by hand"), logo);

        String users = icons.apply("<p>:users:</p>");
        assertTrue(users.contains("<rect width=\"10\""), "book drawing, not Lucide's: " + users);
    }

    @Test
    @DisplayName("a book icon with a script or handler fails rather than being inlined")
    void unsafeBookIconFails(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("bad.svg"), "<svg onload=\"alert(1)\"><path d=\"M0 0\"/></svg>");
        LayoutException e = assertThrows(LayoutException.class,
                () -> new Icons(dir).apply("<p>:bad:</p>"));
        assertTrue(e.getMessage().contains("bad.svg"), e.getMessage());
    }

    @Test
    @DisplayName("the whole bundled set is present")
    void bundledSetLoads() {
        assertTrue(bundled.names().size() > 2000);
        assertTrue(bundled.has("shield") && bundled.has("message-circle"));
    }
}
