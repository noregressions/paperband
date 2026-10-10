package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.FrontMatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Layouts that extend other layouts and change only some of their boxes. */
class LayoutExtensionTest {

    @TempDir
    Path dir;

    private static final String BASE = """
            description: The base.
            class: base-page
            unplaced: ignore
            css: "h1 { color: navy }"
            boxes:
              - { id: title, select: h1, pick: first, class: big }
              - id: steps
                each: ".step"
                boxes:
                  - { id: name, select: h3 }
                  - { id: command, select: "pre.command" }
              - { id: notes, select: ".note", scope: page }
              - { id: body, select: rest }
            """;

    private void layout(String name, String yaml) throws IOException {
        Files.createDirectories(dir.resolve("layouts"));
        Files.writeString(dir.resolve("layouts/" + name + ".yml"), yaml);
    }

    private Layouts load() throws IOException {
        return Layouts.load(dir.resolve("layouts"), PageSpec.A4);
    }

    private String error() {
        return assertThrows(ConfigException.class, this::load).getMessage();
    }

    private static List<String> ids(Layout l) {
        return l.boxes().stream().map(Layout.Box::id).toList();
    }

    @Test
    void changesMergeIntoTheBaseBoxes() throws IOException {
        layout("base", BASE);
        layout("child", """
                extends: base
                boxes:
                  title: { pick: last, class: null }      # a value replaced, a key removed
                  steps/command: { class: cmd }           # a nested box by path
                  notes: null                             # a box removed
                """);
        Layout child = load().get("child");

        assertEquals(List.of("title", "steps", "body"), ids(child));
        Layout.Box title = child.boxes().getFirst();
        assertEquals(-1, title.pick());
        assertNull(title.cssClass());
        assertEquals("h1", title.select(), "untouched keys stay");
        Layout.Box command = child.boxes().get(1).nested().boxes().get(1);
        assertEquals("cmd", command.cssClass());
        assertEquals("base-page", child.cssClass(), "layout keys come from the base unless replaced");
        assertEquals(Layout.Unplaced.IGNORE, child.unplaced());
        assertEquals(List.of("title", "steps", "notes", "body"), ids(load().get("base")), "the base is unchanged");
    }

    @Test
    void addPlacesNewBoxes() throws IOException {
        layout("base", BASE);
        layout("child", """
                extends: base
                add:
                  - { id: footer, text: Draft }
                  - { id: banner, text: New, before: title }
                  - { id: tip, select: ".tip", after: steps }
                """);
        assertEquals(List.of("banner", "title", "steps", "tip", "notes", "body", "footer"), ids(load().get("child")));
    }

    @Test
    void layoutKeysReplaceButCssFromBothIsKept() throws IOException {
        Files.createDirectories(dir.resolve("layouts"));
        Files.writeString(dir.resolve("layouts/base.css"), "p { margin: 0 }");
        layout("base", BASE.replace("css: \"h1 { color: navy }\"", "stylesheet: base.css\ncss: \"h1 { color: navy }\""));
        layout("child", "extends: base\nclass: child-page\ncss: \"h2 { color: red }\"\n");
        Layout child = load().get("child");

        assertEquals("child-page", child.cssClass());
        assertEquals(".layout-child p { margin: 0 }\n.layout-child h1 { color: navy }\n.layout-child h2 { color: red }\n",
                child.css(), "the base's stylesheet and css, then the child's, all confined to the child");
    }

    @Test
    void chainsAndTheDefaultLayout() throws IOException {
        layout("base", BASE);
        layout("middle", "extends: base\nboxes: { notes: null }\n");
        layout("leaf", "extends: middle\nadd: [{ id: extra, text: x }]\n");
        layout("plain", "extends: default\nadd: [{ id: footer, text: End }]\n");
        Layouts layouts = load();
        assertEquals(List.of("title", "steps", "body", "extra"), ids(layouts.get("leaf")));
        assertEquals(List.of("content", "footer"), ids(layouts.get("plain")));
    }

    @Test
    void mistakesNameTheLayoutAndItsBase() throws IOException {
        layout("base", BASE);

        layout("child", "extends: nowhere\n");
        assertTrue(error().startsWith("child.yml: extends: unknown layout \"nowhere\"; available: [base, child, default]"), error());

        layout("child", "extends: base\nboxes: { nope: { class: x } }\n");
        assertEquals("child.yml (extends base): boxes.nope: the layout has no box \"nope\"; its boxes are"
                + " [title, steps, notes, body]", error());

        layout("child", "extends: base\nboxes: { steps/nope: { class: x } }\n");
        assertEquals("child.yml (extends base): boxes.steps/nope: box \"steps\" has no box \"nope\"; its boxes are"
                + " [name, command]", error());

        layout("child", "extends: base\nboxes: { title/inner: { class: x } }\n");
        assertTrue(error().contains("box \"title\" has no nested boxes"), error());

        layout("child", "extends: base\nadd: [{ id: x, text: y, after: nope }]\n");
        assertTrue(error().contains("add[0]: after: no box \"nope\""), error());

        layout("child", "extends: base\nboxes: { title: { text: Hello } }\n");
        assertTrue(error().startsWith("child.yml (extends base): boxes[0]: a text box can't also set select"), error());

        layout("child", "boxes: { title: { class: x } }\n");
        assertTrue(error().contains("a map of changes only applies with extends"), error());

        layout("child", "boxes: [{ id: a, select: h1 }]\nadd: [{ id: b, text: c }]\n");
        assertTrue(error().contains("add only applies with extends"), error());

        layout("child", "extends: base\nadd: [{ id: title, text: dup }]\n");
        assertTrue(error().contains("duplicate box id \"title\""), error());
    }

    @Test
    void rulesCanBeChangedByExtending() throws IOException {
        layout("base", BASE);
        layout("child", "extends: base\nboxes:\n  notes: { when: { not-empty: true }, priority: optional }\n");
        Layout.Rules rules = load().get("child").boxes().get(2).rules();
        assertTrue(rules.when().notEmpty());
        assertEquals(Layout.Priority.OPTIONAL, rules.priority());
    }

    @Test
    void extendingInALoopIsAnError() throws IOException {
        layout("a", "extends: b\n");
        layout("b", "extends: a\n");
        assertEquals("layouts extend each other in a loop: a > b > a", error());
    }

    @Test
    void frontMatterLayoutsCanExtendNamedOnes() throws IOException {
        layout("base", BASE);
        Path md = dir.resolve("docs/a.md");
        Files.createDirectories(md.getParent());
        Files.writeString(md, "---\nlayout:\n  extends: base\n  boxes: { notes: null }\n  css: \"p { x: 1 }\"\n---\n# A\n");
        Map<String, Object> fm = FrontMatter.split(md, Files.readString(md)).data();

        Layout inline = load().withInline(Map.of(Path.of("docs/a.md"), fm), dir, PageSpec.A4).get("inline-docs-a-md");
        assertEquals(List.of("title", "steps", "body"), ids(inline));
        assertTrue(inline.css().endsWith(".layout-inline-docs-a-md p { x: 1 }\n"), inline.css());

        Files.writeString(md, "---\nlayout:\n  extends: base\n  boxes: { nope: null }\n---\n");
        Map<String, Object> bad = FrontMatter.split(md, Files.readString(md)).data();
        ConfigException e = assertThrows(ConfigException.class,
                () -> load().withInline(Map.of(Path.of("docs/a.md"), bad), dir, PageSpec.A4));
        assertTrue(e.getMessage().startsWith("docs/a.md front matter layout (extends base): boxes.nope"), e.getMessage());
    }
}
