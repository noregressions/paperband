package dev.noregressions.paperband;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Index markers, the ids they give elements, and the index ({@code site.index}) they make. */
class IndexTest {

    private static Site site(List<Path> sequence, String... files) throws IOException {
        CombinedAst ast = new CombinedAst();
        for (int i = 0; i < files.length; i += 2) {
            ast.add(Path.of(files[i]), files[i + 1]);
        }
        return new Site(ast, sequence);
    }

    private static String error(String markdown) {
        return assertThrows(IOException.class, () -> new CombinedAst().add(Path.of("a.md"), markdown)).getMessage();
    }

    private static String html(String markdown) throws IOException {
        MarkdownFile f = new CombinedAst().add(Path.of("a.md"), markdown);
        List<org.commonmark.node.Node> blocks = new ArrayList<>();
        for (var n = f.getFirstChild(); n != null; n = n.getNext()) {
            blocks.add(n);
        }
        return MarkdownHtmlRenderer.renderNodes(blocks).strip();
    }

    @Test
    void markedElementsGetIdsInDocumentOrderAvoidingExplicitOnes() throws IOException {
        String out = html("""
                # Removed packages {! index="Java EE"}

                JAXB left in JDK 11. {! index="JAXB"}

                {! id=index-2}
                Taken.

                The [JAXB]{! index} runtime, and `jdeps`{! index id=tool}.
                """);
        assertTrue(out.contains("<h1 data-index=\"Java EE\">"), "a heading keeps its section id: " + out);
        assertTrue(out.contains("<p id=\"index-1\" data-index=\"JAXB\">"), out);
        assertTrue(out.contains("<span id=\"index-3\" class=\"index\">JAXB</span>"), "index-2 is taken: " + out);
        assertTrue(out.contains("<code id=\"tool\" class=\"index\">jdeps</code>"), "an own id is kept: " + out);
    }

    @Test
    void malformedTermsAreErrorsWithTheirLine() {
        assertTrue(error("Text.\n\nMore. {! index=\"a;;b\"}\n").contains("line 3"), "line of the marker");
        assertTrue(error("More. {! index=\"a; ;b\"}\n").contains("empty term"));
        assertTrue(error("More. {! index=\"a!\"}\n").contains("empty term"));
        assertTrue(error("More. {! index=\"a!b!c\"}\n").contains("one ! per term"));
        assertTrue(error("More. {! index=\"`jdeps`\"}\n").contains("write \\` for a backtick"),
                "an unescaped backtick gets a hint, not just \"can't go here\"");
    }

    @Test
    void gathersSortsAndGroupsTerms() throws IOException {
        Site site = site(List.of(Path.of("one.md"), Path.of("two.md")),
                "one.md", """
                        ---
                        chapter: "1"
                        ---
                        Use `jdeps` here. {! index="\\`jdeps\\`; JAXB!runtime"}

                        Flags. {! index="--add-opens; 64-bit"}
                        """,
                "two.md", """
                        ---
                        chapter: "2"
                        ---
                        JAXB again. {! index="jaxb; Zebra"}

                        And again. {! index="JAXB!api; JAXB!Runtime"}
                        """);
        Index index = site.getIndex();
        assertEquals(List.of("64-bit", "--add-opens", "JAXB", "`jdeps`", "Zebra"),
                index.getTerms().stream().map(Index.Term::getTerm).toList(),
                "sorted ignoring case, Markdown and leading punctuation; same-sorting terms merge as first written");
        assertEquals(List.of("#", "A", "J", "Z"), index.getLetters().stream().map(Index.Letter::getLetter).toList());

        Index.Term jaxb = index.getTerms().get(2);
        assertEquals(List.of("two-md--index-1"), jaxb.getLocators().stream().map(Index.Locator::getAnchor).toList());
        assertEquals(List.of("api", "runtime"), jaxb.getSubentries().stream().map(Index.Term::getTerm).toList());
        Index.Term runtime = jaxb.getSubentries().get(1);
        assertEquals(List.of("one-md--index-1", "two-md--index-2"),
                runtime.getLocators().stream().map(Index.Locator::getAnchor).toList(), "in sequence, then document, order");
        assertEquals("2", runtime.getLocators().get(1).getFile().getPage().get("chapter"));
    }

    @Test
    void aHeadingIsLinkedByItsSectionId() throws IOException {
        Site site = site(null, "a.md", "# Removed packages {! index=X}\n\n## Inner {! index=Y}\n\nText. {! index=Z}\n");
        assertEquals(List.of("a-md--removed-packages", "a-md--inner", "a-md--index-1"), site.getIndex().getTerms()
                .stream().map(t -> t.getLocators().getFirst().getAnchor()).toList());
    }

    @Test
    void repeatsMarkLocatorsInTheSameFileAsTheOneBefore() throws IOException {
        Site site = site(null, "a.md", "One. {! index=X}\n\nTwo. {! index=X}\n", "b.md", "Three. {! index=X}\n");
        assertEquals(List.of(false, true, false), site.getIndex().getTerms().getFirst().getLocators().stream()
                .map(Index.Locator::isRepeat).toList());
    }

    @Test
    void onlyTheSequenceIsIndexedInItsOrder() throws IOException {
        Site site = site(List.of(Path.of("b.md"), Path.of("a.md")),
                "a.md", "A. {! index=X}\n", "b.md", "B. {! index=X}\n", "hidden.md", "H. {! index=Y}\n");
        assertEquals(1, site.getIndex().getTerms().size(), "hidden.md isn't written out");
        assertEquals(List.of("b-md--index-1", "a-md--index-1"), site.getIndex().getTerms().getFirst().getLocators()
                .stream().map(Index.Locator::getAnchor).toList());
    }

    @Test
    void thePrePassHasNoIndex() {
        Site pre = Site.ofFrontMatter(java.util.Map.of(Path.of("a.md"), java.util.Map.of()));
        IllegalStateException e = assertThrows(IllegalStateException.class, pre::getIndex);
        assertEquals(Site.NO_CONTENT_YET, e.getMessage());
    }

    @Test
    void postPassTemplatesBuildTheIndexPage() throws IOException {
        MarkdownPipeline.Result r = new MarkdownPipeline(MarkdownPipeline.Config.defaults()).run(List.of(
                new MarkdownPipeline.Source(Path.of("ch.md"), "---\nchapter: \"3.6\"\n---\nJAXB. {! index=JAXB}\n"),
                new MarkdownPipeline.Source(Path.of("index.md"), """
                        [% for t in site.index.terms %]
                        - [[ t.term ]], [% for at in t.locators %][% set link = "[" ~ at.file.page.chapter ~ "](#" ~ at.anchor ~ ")" %][[ link ]]{! pageref}[% endfor %]
                        [% endfor %]
                        """)));
        String index = r.texts().get(Path.of("index.md")).post();
        assertTrue(index.contains("- JAXB, [3.6](#ch-md--index-1){! pageref}"), index);
    }

    private static Site auto(String yaml, String... files) throws IOException {
        AutoIndex rules = dev.noregressions.paperband.layout.IndexSpec.parse(FrontMatter.parseYaml(yaml), "index.yml");
        CombinedAst ast = new CombinedAst(Preprocessor.NONE, rules);
        for (int i = 0; i < files.length; i += 2) {
            ast.add(Path.of(files[i]), files[i + 1]);
        }
        return new Site(ast);
    }

    private static List<String> places(Site site, String term) {
        return site.getIndex().getTerms().stream().filter(t -> t.getTerm().equals(term)).findFirst()
                .map(t -> t.getLocators().stream().map(Index.Locator::getAnchor).toList()).orElse(List.of());
    }

    @Test
    void termsAreFoundAtTheirFirstDiscussionInEachFile() throws IOException {
        Site site = auto("""
                        terms:
                          - JAXB
                          - term: "`jdeps`"
                        """,
                "a.md", "# A\n\nNothing here.\n\nJAXB first.\n\n## About JAXB\n\nJAXB again, and `jdeps`.\n",
                "b.md", "Intro.\n\n- an item on JAXB\n- more\n");
        assertEquals(List.of("a-md--about-jaxb", "b-md--index-1"), places(site, "JAXB"),
                "a heading wins over an earlier paragraph; a list item is indexed as itself");
        assertEquals(List.of("a-md--index-1"), places(site, "`jdeps`"), "found by its plain text, in inline code");
    }

    @Test
    void passingMentionsHomesSkippedSectionsAndFiles() throws IOException {
        Site site = auto("""
                        files: ["docs/*.md"]
                        skip: [References]
                        mentions: 2
                        terms:
                          - JAXB
                          - term: Security Manager
                            match: "Security ?Manager"
                            home: [docs/sm.md]
                        """,
                "docs/once.md", "JAXB once.\n",
                "docs/twice.md", "JAXB, and JAXB.\n",
                "docs/sm.md", "The opening paragraph.\n\nNo mention of the subject by name.\n",
                "docs/refs.md", "Intro.\n\n## References\n\nJAXB JAXB.\n\n### Deeper\n\nJAXB.\n\n## After\n\nSecurityManager, SecurityManager.\n",
                "other/x.md", "JAXB JAXB.\n");
        assertEquals(List.of("docs-twice-md--index-1"), places(site, "JAXB"),
                "one mention isn't enough; skipped sections and unsearched files aren't searched");
        assertEquals(List.of("docs-sm-md--index-1", "docs-refs-md--index-1"), places(site, "Security Manager"),
                "a home is indexed at its opening paragraph; the regular expression finds the other spelling");
    }

    @Test
    void foundTermsJoinHandPlacedMarkers() throws IOException {
        Site site = auto("terms: [JAXB]\n", "a.md", "JAXB, by hand too. {! index=\"Java EE\"}\n");
        assertEquals(List.of("a-md--index-1"), places(site, "JAXB"));
        assertEquals(List.of("a-md--index-1"), places(site, "Java EE"));
    }

    @Test
    void theIndexFileIsChecked() {
        String e = assertThrows(dev.noregressions.paperband.layout.ConfigException.class, () -> dev.noregressions.paperband.layout.IndexSpec.parse(
                FrontMatter.parseYaml("terms:\n  - term: X\n    match: \"(\"\n"), "index.yml")).getMessage();
        assertTrue(e.contains("not a valid regular expression"), e);
        e = assertThrows(dev.noregressions.paperband.layout.ConfigException.class, () -> dev.noregressions.paperband.layout.IndexSpec.parse(
                FrontMatter.parseYaml("terms: [\"a;b\"]\n"), "index.yml")).getMessage();
        assertTrue(e.contains("isn't a term"), e);
        e = assertThrows(dev.noregressions.paperband.layout.ConfigException.class, () -> dev.noregressions.paperband.layout.IndexSpec.parse(
                FrontMatter.parseYaml("mentions: 0\nterms: [a]\n"), "index.yml")).getMessage();
        assertTrue(e.contains("whole number from 1"), e);
    }
}
