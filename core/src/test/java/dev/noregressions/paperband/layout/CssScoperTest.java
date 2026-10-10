package dev.noregressions.paperband.layout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CssScoperTest {

    private static String scope(String css) throws ConfigException {
        return CssScoper.scope(css, ".layout-x", "x.css");
    }

    private static String error(String css) {
        return assertThrows(ConfigException.class, () -> scope(css)).getMessage();
    }

    @Test
    void prefixesEverySelector() throws ConfigException {
        assertEquals(".layout-x h1, .layout-x .lede p { color: red; }\n", scope("h1, .lede p { color: red; }"));
        assertEquals(".layout-x > .box-a {a:1}\n.layout-x.grid {b:2}\n.layout-x:hover {c:3}\n",
                scope("& > .box-a {a:1}\n&.grid {b:2}\n:scope:hover {c:3}"));
        assertEquals(".layout-x a[href*=\",\"], .layout-x :is(h1, h2) {x:1}\n", scope("a[href*=\",\"], :is(h1, h2) {x:1}"),
                "commas inside brackets and strings don't split selectors");
    }

    @Test
    void scopesInsideMediaAndKeepsGlobalAtRules() throws ConfigException {
        assertEquals("@media (max-width: 700px) {\n.layout-x p {a:1}\n}\n", scope("@media (max-width: 700px) { p {a:1} }"));
        assertEquals("@keyframes spin { from { transform: rotate(0) } }\n",
                scope("@keyframes spin { from { transform: rotate(0) } }"));
        assertEquals("@font-face { font-family: X; src: url(\"a}.woff\") }\n",
                scope("@font-face { font-family: X; src: url(\"a}.woff\") }"));
        assertEquals(".layout-x p {a:1}\n", scope("@charset \"utf-8\";\n/* a comment { } */ p {a:1}"));
    }

    @Test
    void rejectsWhatCantBeConfined() {
        assertEquals("x.css line 2: \"body\" can't be styled from a layout stylesheet (it's confined to the layout's"
                + " content); use the manifest's stylesheets", error("p {}\nbody { margin: 0 }"));
        assertTrue(error(":root { --a: 1 }").contains("\":root\" can't be styled"));
        assertTrue(error("html.dark p {}").contains("\"html.dark p\" can't be styled"));
        assertTrue(error("@import url(x.css);").contains("@import isn't allowed"));
        assertTrue(error("@scope (.a) { p {} }").contains("\"@scope\" isn't supported"));
        assertTrue(error("p { color: red").contains("missing }"));
        assertTrue(error("p }").contains("rule \"p }\" has no { ... }") || error("p }").contains("unexpected }"));
        assertDoesNotThrow(() -> scope(".body-text {} .htmlish {}"), "only the html/body elements themselves are refused");
    }
}
