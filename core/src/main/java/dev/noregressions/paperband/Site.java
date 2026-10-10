package dev.noregressions.paperband;

import org.commonmark.node.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A read-only, template-friendly view of the files being processed, exposed to templates as
 * {@code site}.
 * <ul>
 *   <li>In the post pass it wraps the draft {@link CombinedAst}, so file content can be read:
 *       <pre>
 * [% for item in site.file("glossary.md").section("terms").list.items %]
 * - [[ item ]]
 * [% endfor %]</pre></li>
 *   <li>In the pre pass ({@link #ofFrontMatter}) only each file's path and front matter are
 *       known, e.g. {@code {{ site.file("intro.md").page.title }}}; asking for content there
 *       is an error.</li>
 * </ul>
 */
public final class Site {

    static final String NO_CONTENT_YET =
            "file content isn't available in the pre pass; read it with [[ ]] / [% %] in the post pass";

    private final List<FileView> files;
    private final List<FileView> sequence;
    private Index index;

    public Site(CombinedAst ast) {
        this(ast, null);
    }

    /** @param sequence the files being written out, in order; null means all of them */
    public Site(CombinedAst ast, List<Path> sequence) {
        this(ast.getFiles().stream().map(FileView::new).toList(), sequence);
    }

    private Site(List<FileView> files, List<Path> sequence) {
        this.files = files;
        if (sequence == null) {
            this.sequence = files;
        } else {
            List<FileView> inOrder = new ArrayList<>();
            for (Path p : sequence) {
                files.stream().filter(f -> f.path.equals(p)).findFirst().ifPresent(inOrder::add);
            }
            this.sequence = List.copyOf(inOrder);
        }
    }

    /** A site whose files have front matter but no content yet, for the pre pass. */
    public static Site ofFrontMatter(Map<Path, Map<String, Object>> frontMatter) {
        return ofFrontMatter(frontMatter, null);
    }

    /** @param sequence the files being written out, in order; null means all of them */
    public static Site ofFrontMatter(Map<Path, Map<String, Object>> frontMatter, List<Path> sequence) {
        return new Site(frontMatter.entrySet().stream().map(e -> new FileView(e.getKey(), e.getValue())).toList(),
                sequence);
    }

    /** Every source file, in source order. */
    public List<FileView> getFiles() {
        return files;
    }

    /**
     * The files being written out, in output order (a manifest's sequence); without a
     * manifest, the same as {@link #getFiles()}. Hidden and unused sources aren't in it.
     */
    public List<FileView> getSequence() {
        return sequence;
    }

    /**
     * The index of the files being written out ({@link Index}): every {@code {! index=...}}
     * marker's terms, sorted and grouped by letter. Post pass only.
     */
    public Index getIndex() {
        if (index == null) {
            for (FileView f : sequence) {
                f.blocks(); // the pre pass has no content to index
            }
            index = Index.of(sequence);
        }
        return index;
    }

    /** The file whose path is {@code name}, or ends with it (e.g. {@code "a.md"} for {@code docs/a.md}). */
    public FileView file(String name) {
        for (FileView f : files) {
            if (f.path.toString().equals(name)) {
                return f;
            }
        }
        List<FileView> matches = files.stream().filter(f -> f.path.endsWith(name)).toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        throw new IllegalArgumentException((matches.isEmpty() ? "no file matches \"" : "several files match \"")
                + name + "\"; files are " + files.stream().map(FileView::getPath).toList());
    }

    /** A run of top-level blocks: a whole file or one section of it. */
    public abstract static class Content {

        private final List<Node> blocks;

        /** @param blocks null when the content isn't known yet (pre pass) */
        Content(List<Node> blocks) {
            this.blocks = blocks;
        }

        List<Node> blocks() {
            if (blocks == null) {
                throw new IllegalStateException(NO_CONTENT_YET);
            }
            return blocks;
        }

        public List<ListView> getLists() {
            return blocks().stream().filter(ListBlock.class::isInstance).map(b -> new ListView((ListBlock) b)).toList();
        }

        /** The first list, or null if there is none. */
        public ListView getList() {
            List<ListView> lists = getLists();
            return lists.isEmpty() ? null : lists.getFirst();
        }

        public List<String> getParagraphs() {
            return blocks().stream().filter(Paragraph.class::isInstance).map(Site::text).toList();
        }

        public List<CodeView> getCode() {
            List<CodeView> code = new ArrayList<>();
            for (Node b : blocks()) {
                if (b instanceof FencedCodeBlock f) {
                    code.add(new CodeView(f.getInfo(), f.getLiteral()));
                } else if (b instanceof IndentedCodeBlock i) {
                    code.add(new CodeView("", i.getLiteral()));
                }
            }
            return code;
        }

        /** Plain text of all blocks, one per line. */
        public String getText() {
            return blocks().stream().map(Site::text).collect(Collectors.joining("\n"));
        }

        @Override
        public String toString() {
            return blocks == null ? "" : getText();
        }
    }

    public static final class FileView extends Content {

        private final Path path;
        private final Map<String, Object> page;
        private final List<Section> sections;
        private final MarkdownFile file;

        /** Pre pass: front matter only. */
        FileView(Path path, Map<String, Object> page) {
            super(null);
            this.path = path;
            this.page = page;
            this.sections = null;
            this.file = null;
        }

        FileView(MarkdownFile file) {
            super(topLevelBlocks(file));
            this.file = file;
            this.path = file.getPath();
            this.page = file.getFrontMatter();
            this.sections = new ArrayList<>();

            List<Node> blocks = blocks();
            for (int i = 0; i < blocks.size(); i++) {
                if (blocks.get(i) instanceof Heading h) {
                    int end = i + 1;
                    while (end < blocks.size()
                            && !(blocks.get(end) instanceof Heading next && next.getLevel() <= h.getLevel())) {
                        end++;
                    }
                    sections.add(new Section(file.headingId(h), h, blocks.subList(i + 1, end)));
                }
            }
        }

        public String getPath() {
            return path.toString();
        }

        /** The parsed file; null in the pre pass. */
        MarkdownFile file() {
            return file;
        }

        /**
         * The id of the file's page in the output, for links between pages:
         * {@code [{{ f.page.title }}](#{{ f.anchor }})}.
         */
        public String getAnchor() {
            return dev.noregressions.paperband.layout.LayoutEngine.anchor(path.toString().replace('\\', '/'));
        }

        public Map<String, Object> getPage() {
            return page;
        }

        /** Every heading's section, in document order (nested sections included). */
        public List<Section> getSections() {
            blocks();
            return sections;
        }

        /** The section whose id ({@code "my-heading"}) or title ({@code "My Heading"}, any case) matches. */
        public Section section(String name) {
            for (Section s : getSections()) {
                if (s.id.equals(name) || s.title.equalsIgnoreCase(name)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("no section \"" + name + "\" in " + path + "; sections are "
                    + sections.stream().map(Section::getId).toList());
        }

        private static List<Node> topLevelBlocks(MarkdownFile file) {
            List<Node> blocks = new ArrayList<>();
            for (Node n = file.getFirstChild(); n != null; n = n.getNext()) {
                if (!(n instanceof FrontMatterBlock)) {
                    blocks.add(n);
                }
            }
            return blocks;
        }
    }

    /** A heading and the blocks after it, up to the next heading of the same or a higher level. */
    public static final class Section extends Content {

        private final String id;
        private final String title;
        private final int level;

        Section(String id, Heading heading, List<Node> blocks) {
            super(blocks);
            this.id = id;
            this.title = text(heading);
            this.level = heading.getLevel();
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public int getLevel() {
            return level;
        }
    }

    public static final class ListView {

        private final ListBlock list;

        ListView(ListBlock list) {
            this.list = list;
        }

        public boolean isOrdered() {
            return list instanceof OrderedList;
        }

        public List<Item> getItems() {
            List<Item> items = new ArrayList<>();
            for (Node n = list.getFirstChild(); n != null; n = n.getNext()) {
                items.add(new Item((ListItem) n));
            }
            return items;
        }

        @Override
        public String toString() {
            return getItems().toString();
        }
    }

    /** A list item. Prints as its own text, excluding any nested lists. */
    public static final class Item {

        private final ListItem item;

        Item(ListItem item) {
            this.item = item;
        }

        public String getText() {
            List<String> parts = new ArrayList<>();
            for (Node n = item.getFirstChild(); n != null; n = n.getNext()) {
                if (!(n instanceof ListBlock)) {
                    parts.add(text(n));
                }
            }
            return String.join(" ", parts);
        }

        /** Lists nested inside this item. */
        public List<ListView> getLists() {
            List<ListView> lists = new ArrayList<>();
            for (Node n = item.getFirstChild(); n != null; n = n.getNext()) {
                if (n instanceof ListBlock l) {
                    lists.add(new ListView(l));
                }
            }
            return lists;
        }

        @Override
        public String toString() {
            return getText();
        }
    }

    public static final class CodeView {

        private final String language;
        private final String code;

        CodeView(String language, String code) {
            this.language = language == null ? "" : language;
            this.code = code;
        }

        public String getLanguage() {
            return language;
        }

        public String getCode() {
            return code;
        }

        @Override
        public String toString() {
            return code;
        }
    }

    /** Plain text of a node: literal text and code, with line breaks as spaces. */
    public static String text(Node node) {
        return text(node, null);
    }

    /** Plain text, showing each {@link Variants} group's variant for {@code prefer} (null: the longest). */
    public static String text(Node node, Variants.Variant prefer) {
        StringBuilder sb = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override
            public void visit(CustomBlock block) {
                if (Variants.isGroup(block)) {
                    Node chosen = Variants.choose(block, prefer);
                    if (chosen != null) {
                        chosen.accept(this);
                    }
                } else {
                    visitChildren(block);
                }
            }

            @Override
            public void visit(Text text) {
                sb.append(text.getLiteral());
            }

            @Override
            public void visit(Code code) {
                sb.append(code.getLiteral());
            }

            @Override
            public void visit(FencedCodeBlock code) {
                sb.append(code.getLiteral());
            }

            @Override
            public void visit(IndentedCodeBlock code) {
                sb.append(code.getLiteral());
            }

            @Override
            public void visit(SoftLineBreak lineBreak) {
                sb.append(' ');
            }

            @Override
            public void visit(HardLineBreak lineBreak) {
                sb.append(' ');
            }

            @Override
            public void visit(Paragraph paragraph) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                visitChildren(paragraph);
            }

            @Override
            public void visit(CustomNode node) {
                if (node instanceof org.commonmark.ext.gfm.tables.TableCell && !sb.isEmpty()) {
                    sb.append(' '); // cells are words apart
                }
                visitChildren(node);
            }
        });
        return sb.toString().strip();
    }

    /** GitHub-style heading id: lower case, punctuation dropped, spaces to hyphens. */
    public static String slug(String title) {
        return title.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N} _-]", "")
                .strip()
                .replace(' ', '-');
    }
}
