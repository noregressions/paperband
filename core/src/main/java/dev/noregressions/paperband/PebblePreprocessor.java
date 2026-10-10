package dev.noregressions.paperband;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.lexer.Syntax;
import io.pebbletemplates.pebble.loader.FileLoader;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a markdown file through the Pebble template engine.
 * <p>
 * There are two passes, each with its own delimiters so neither interprets the other's tags:
 * <ul>
 *   <li>{@link Pass#PRE}: {@code {{ value }}}, {@code {% tag %}}, {@code {# comment #}}. Runs on
 *       each file before it is parsed.</li>
 *   <li>{@link Pass#POST}: {@code [[ value ]]}, {@code [% tag %]}, {@code [# comment #]}. Runs once
 *       every file has been pre-processed and parsed into a draft AST.</li>
 * </ul>
 * Templates can use:
 * <ul>
 *   <li>{@code page.<key>}: the file's own front matter, as typed YAML (see {@link FrontMatter});</li>
 *   <li>{@code file}: the file's path;</li>
 *   <li>any global variables passed to the constructor, and any {@code extra} variables passed to
 *       {@link #process(Path, String, Map)}; {@link MarkdownPipeline} uses these for
 *       {@code site}, {@code data}, {@code env} and {@code build};</li>
 *   <li>{@code include} and {@code extends}: resolved against the templates directory
 *       ({@value #DEFAULT_TEMPLATES} by default).</li>
 * </ul>
 * Only the body after the front matter is templated; the front matter is put back
 * unchanged afterwards. This keeps it in the output when a file uses
 * {@code extends}, where Pebble discards everything outside {@code block} tags.
 * <p>
 * Lines holding only tags or comments ({@code {% if %}}, {@code {% endfor %}}, {@code {# ... #}})
 * are removed entirely, so they leave no blank line behind. All other newlines are kept, so
 * {@code - {{ item }}} lines stay separate list items and {@code text{% endblock %}} doesn't
 * swallow the line break after it.
 * <p>
 * Fenced code blocks aren't templated: code is full of brackets and braces ({@code [[ -f x ]]},
 * {@code "\[[a-z]+\]"}, {@code {{ }}} in a Go template) that would otherwise be read as tags. A
 * block that wants templating says so after its language: {@code ```bash {! template}}. Inline
 * code is templated, so {@code `{{ version }}`} shows a value as code.
 * <p>
 * HTML auto-escaping is off, since the output is markdown rather than HTML.
 * Use {@code verbatim} to keep a pass's own delimiters as literal text.
 */
public class PebblePreprocessor implements Preprocessor {

    public static final String DEFAULT_TEMPLATES = "templates";

    public enum Pass {
        PRE("{{", "}}", "{%", "%}", "{#", "#}"),
        POST("[[", "]]", "[%", "%]", "[#", "#]");

        private final Syntax syntax;
        private final Pattern tagOnlyLine;

        Pass(String printOpen, String printClose, String executeOpen, String executeClose,
             String commentOpen, String commentClose) {
            this.syntax = new Syntax.Builder()
                    .setPrintOpenDelimiter(printOpen).setPrintCloseDelimiter(printClose)
                    .setExecuteOpenDelimiter(executeOpen).setExecuteCloseDelimiter(executeClose)
                    .setCommentOpenDelimiter(commentOpen).setCommentCloseDelimiter(commentClose)
                    .setEnableNewLineTrimming(false)
                    .build();
            // A line made up only of tags and comments plus whitespace; group 1 is the tags.
            this.tagOnlyLine = Pattern.compile("^[ \\t]*((?:(?:" + delimited(executeOpen, executeClose) + "|"
                    + delimited(commentOpen, commentClose) + ")[ \\t]*)+)\\r?\\n", Pattern.MULTILINE);
        }

        /** {@code open ... close}, possibly over several lines, never running past the first {@code close}. */
        private static String delimited(String open, String close) {
            return Pattern.quote(open) + "(?s:(?:(?!" + Pattern.quote(close) + ").)*)" + Pattern.quote(close);
        }
    }

    private final Pass pass;
    private final PebbleEngine engine;
    private final Map<String, Object> globals;

    public PebblePreprocessor(Map<String, ?> globals) {
        this(Path.of(DEFAULT_TEMPLATES), globals);
    }

    public PebblePreprocessor(Path templatesDir, Map<String, ?> globals) {
        this(Pass.PRE, templatesDir, globals);
    }

    public PebblePreprocessor(Pass pass, Path templatesDir, Map<String, ?> globals) {
        this.pass = pass;
        this.engine = new PebbleEngine.Builder()
                .loader(new TrimTagLinesFileLoader(templatesDir, this::trimTagLines))
                .syntax(pass.syntax)
                .autoEscaping(false)
                .build();
        this.globals = Map.copyOf(globals);
    }

    @Override
    public String process(Path path, String source) throws IOException {
        return process(path, source, Map.of());
    }

    /** @param extra additional template variables, such as {@code site} for the post pass */
    public String process(Path path, String source, Map<String, ?> extra) throws IOException {
        FrontMatter.Split split = FrontMatter.split(path, source);

        Map<String, Object> context = new HashMap<>(globals);
        context.put("page", split.data());
        context.put("file", path.toString());
        context.putAll(extra);

        StringWriter out = new StringWriter();
        List<String> code = new ArrayList<>();
        try {
            String body = trimTagLines(protectCode(split.body(), code));
            if (pass == Pass.POST) {
                body = body.replace(NUMBER_LINK, NUMBER_LINK_STAND_IN); // [#](...) is a link, not a [# comment #]
            }
            engine.getLiteralTemplate(body).evaluate(out, context);
        } catch (PebbleException e) {
            throw new IOException("Pebble " + pass.name().toLowerCase() + "-pass error in " + path + ": "
                    + describe(e), e);
        }
        return split.block() + restoreCode(out.toString(), code).replace(NUMBER_LINK_STAND_IN, NUMBER_LINK);
    }

    /**
     * A link whose text is {@code #} ({@link PageLinks}): it starts like a post-pass comment, {@code [# ... #]},
     * which never has {@code ](} straight after the {@code #}, so it's kept out of the template's way.
     */
    private static final String NUMBER_LINK = "[#](";
    private static final String NUMBER_LINK_STAND_IN = "\uE012](";

    /** The marker that asks for a fenced code block to be templated: {@code ```bash {! template}}. */
    static final String TEMPLATE_TAG = "template";

    /** Finds fenced code blocks; only line positions matter, so no extensions are needed. */
    private static final Parser CODE_FINDER = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build();

    /** Marks a stand-in for a fenced code block while the template runs; private-use, so never in real text. */
    private static final char CODE_OPEN = '\uE010';
    private static final char CODE_CLOSE = '\uE011';

    /**
     * Swaps each fenced code block (fences included) for a stand-in that keeps its line count, so
     * line numbers in template errors still match, and records the block in {@code code}.
     */
    static String protectCode(String body, List<String> code) {
        Node doc = CODE_FINDER.parse(body);
        List<int[]> ranges = new ArrayList<>(); // first and last line, 0-based
        doc.accept(new AbstractVisitor() {
            @Override
            public void visit(FencedCodeBlock block) {
                List<SourceSpan> spans = block.getSourceSpans();
                if (!spans.isEmpty() && !wantsTemplate(block.getInfo())) {
                    ranges.add(new int[]{spans.getFirst().getLineIndex(), spans.getLast().getLineIndex()});
                }
            }
        });
        if (ranges.isEmpty()) {
            return body;
        }
        String[] lines = body.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int line = 0;
        for (int[] r : ranges) {
            for (; line < r[0]; line++) {
                sb.append(lines[line]).append('\n');
            }
            StringBuilder block = new StringBuilder();
            for (; line <= r[1] && line < lines.length; line++) {
                block.append(lines[line]).append(line < lines.length - 1 ? "\n" : "");
            }
            int newlines = (int) block.chars().filter(c -> c == '\n').count();
            sb.append(CODE_OPEN).append(code.size()).append(CODE_CLOSE).append("\n".repeat(newlines));
            code.add(block.toString());
        }
        for (; line < lines.length; line++) {
            sb.append(lines[line]).append(line < lines.length - 1 ? "\n" : "");
        }
        return sb.toString();
    }

    /** Puts the code blocks back, wherever the template left their stand-ins (once, twice in a loop, or not at all). */
    static String restoreCode(String text, List<String> code) {
        if (code.isEmpty()) {
            return text;
        }
        Matcher m = Pattern.compile(CODE_OPEN + "(\\d+)" + CODE_CLOSE + "(\n*)").matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String block = code.get(Integer.parseInt(m.group(1)));
            int keep = m.group(2).length() - (int) block.chars().filter(c -> c == '\n').count();
            m.appendReplacement(sb, Matcher.quoteReplacement(block + "\n".repeat(Math.max(0, keep))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** True if a fence's info string has a marker with the {@value #TEMPLATE_TAG} tag. */
    private static boolean wantsTemplate(String info) {
        int start = info == null ? -1 : info.indexOf("{!");
        if (start < 0) {
            return false;
        }
        int end = Attributes.end(info, start);
        try {
            return end > 0 && Attributes.parseMarker(info.substring(start, end)).tags().contains(TEMPLATE_TAG);
        } catch (Attributes.MarkerException e) {
            return false; // reported when the file's markers are read
        }
    }

    /**
     * Pebble's message, plus the root cause's if Pebble wrapped one: an exception thrown by a
     * method a template called (e.g. {@link Site#file}) explains far more than "Could not call file".
     */
    private static String describe(PebbleException e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = e.getMessage().strip();
        return root == e || root.getMessage() == null ? message : message + ": " + root.getMessage();
    }

    /**
     * Pebble's own newline trimming drops the newline after every tag, including
     * print tags, which joins lines like {@code - {{ item }}}. Instead, this removes
     * the newline (and indentation) only from lines that hold nothing but tags.
     */
    String trimTagLines(String template) {
        return pass.tagOnlyLine.matcher(template).replaceAll(m -> Matcher.quoteReplacement(m.group(1).strip()));
    }

    /** Loads templates for {@code extends}/{@code include}, applying {@link #trimTagLines}. */
    private static final class TrimTagLinesFileLoader extends FileLoader {

        private final UnaryOperator<String> trim;

        TrimTagLinesFileLoader(Path dir, UnaryOperator<String> trim) {
            super(dir.toAbsolutePath().toString());
            this.trim = trim;
        }

        @Override
        public Reader getReader(String cacheKey) {
            try (Reader in = super.getReader(cacheKey)) {
                StringWriter text = new StringWriter();
                in.transferTo(text);
                return new StringReader(trim.apply(text.toString()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
