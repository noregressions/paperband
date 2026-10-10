package dev.noregressions.paperband;

import org.commonmark.node.Document;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a series of markdown files and merges them into a single AST:
 *
 * <pre>
 * Document
 * ├── MarkdownFile (a.md, front matter)
 * │   ├── FrontMatterBlock
 * │   ├── Heading
 * │   └── ...
 * └── MarkdownFile (b.md, front matter)
 *     └── ...
 * </pre>
 */
public class CombinedAst {

    private final Document root = new Document();
    private final List<MarkdownFile> files = new ArrayList<>();
    private final Preprocessor preprocessor;
    private final AutoIndex autoIndex;

    /** The commonmark extensions in use: GitHub-style pipe tables. Renderers use the same list. */
    public static final List<org.commonmark.Extension> EXTENSIONS =
            List.of(org.commonmark.ext.gfm.tables.TablesExtension.create());

    private static final Parser PARSER = Parser.builder()
            .extensions(EXTENSIONS)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .customBlockParserFactory(new Div.Factory())
            .build();

    public CombinedAst() {
        this(Preprocessor.NONE);
    }

    /** @param preprocessor applied to each file's source before it is parsed */
    public CombinedAst(Preprocessor preprocessor) {
        this(preprocessor, null);
    }

    /** @param autoIndex index terms to find in each file as it's parsed; null for none */
    public CombinedAst(Preprocessor preprocessor, AutoIndex autoIndex) {
        this.preprocessor = preprocessor;
        this.autoIndex = autoIndex;
    }

    public static CombinedAst parse(List<Path> paths) throws IOException {
        return parse(paths, Preprocessor.NONE);
    }

    public static CombinedAst parse(List<Path> paths, Preprocessor preprocessor) throws IOException {
        CombinedAst ast = new CombinedAst(preprocessor);
        for (Path path : paths) {
            ast.add(path, Files.readString(path, StandardCharsets.UTF_8));
        }
        return ast;
    }

    /**
     * Preprocesses and parses {@code markdown}, then appends it to the combined AST
     * under a new {@link MarkdownFile} node.
     *
     * @throws IOException if preprocessing fails, the front matter isn't valid YAML, or a
     *                     {@code {! ...}} marker is malformed or misplaced (see {@link Markers})
     */
    public MarkdownFile add(Path path, String markdown) throws IOException {
        FrontMatter.Split split = FrontMatter.split(path, preprocessor.process(path, markdown));
        Map<Node, Attributes> attributes;
        Node doc;
        try {
            // Blank lines in place of the front matter keep source line numbers matching the file.
            doc = PARSER.parse(Markers.protectEscapes(split.paddedBody()));
            attributes = Markers.apply(doc, autoIndex == null ? null : d -> autoIndex.find(path, d));
        } catch (Attributes.MarkerException e) {
            throw new IOException(path + (e.line() > 0 ? " line " + e.line() : "") + ": " + e.getMessage(), e);
        }

        MarkdownFile file = new MarkdownFile(path, split.data(), attributes);
        if (!split.block().isEmpty()) {
            FrontMatterBlock fm = new FrontMatterBlock(split.data());
            fm.setSourceSpans(List.of(SourceSpan.of(0, 0, 0, split.block().length())));
            file.appendChild(fm);
        }
        // appendChild unlinks the node from its old parent, so grab next first.
        Node child = doc.getFirstChild();
        while (child != null) {
            Node next = child.getNext();
            file.appendChild(child);
            child = next;
        }
        root.appendChild(file);
        files.add(file);
        return file;
    }

    public Document getRoot() {
        return root;
    }

    public List<MarkdownFile> getFiles() {
        return List.copyOf(files);
    }

    /** Front matter of every file, keyed by path, in command-line order. */
    public Map<Path, Map<String, Object>> getFrontMatter() {
        Map<Path, Map<String, Object>> all = new LinkedHashMap<>();
        for (MarkdownFile f : files) {
            all.put(f.getPath(), f.getFrontMatter());
        }
        return all;
    }
}
