package dev.noregressions.paperband;

import org.commonmark.node.Block;
import org.commonmark.node.CustomBlock;
import org.commonmark.parser.SourceLine;
import org.commonmark.parser.block.AbstractBlockParser;
import org.commonmark.parser.block.AbstractBlockParserFactory;
import org.commonmark.parser.block.BlockContinue;
import org.commonmark.parser.block.BlockStart;
import org.commonmark.parser.block.MatchedBlockParser;
import org.commonmark.parser.block.ParserState;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fenced div: blocks grouped under one set of handles.
 *
 * <pre>
 * ::: {! instruction id=get-it}      or   ::: instruction
 * Download the latest release.
 *
 * - and anything else
 * :::
 * </pre>
 *
 * Divs can nest; a closing {@code :::} closes the innermost open one. A div that isn't closed
 * before its container ends (or the file ends) is an error.
 */
public class Div extends CustomBlock {

    private final Attributes attributes;
    private final int line;
    private boolean closed;

    Div(Attributes attributes, int line) {
        this.attributes = attributes;
        this.line = line;
    }

    public Attributes getAttributes() {
        return attributes;
    }

    /** 1-based line of the opening {@code :::}, or -1. */
    int line() {
        return line;
    }

    boolean isClosed() {
        return closed;
    }

    private static final Pattern OPEN = Pattern.compile(":{3,}\\s*(\\S.*?)\\s*");
    private static final Pattern CLOSE = Pattern.compile(" {0,3}:{3,}\\s*");

    /** Registers {@code :::} with the commonmark parser. */
    static final class Factory extends AbstractBlockParserFactory {

        @Override
        public BlockStart tryStart(ParserState state, MatchedBlockParser matched) {
            if (state.getIndent() >= 4) {
                return BlockStart.none();
            }
            CharSequence line = state.getLine().getContent();
            Matcher m = OPEN.matcher(line.subSequence(state.getNextNonSpaceIndex(), line.length()));
            if (!m.matches()) {
                return BlockStart.none();
            }
            int lineNumber = lineOf(state.getLine());
            Attributes attributes;
            try {
                String spec = m.group(1);
                attributes = spec.startsWith("{!") ? whole(spec) : Attributes.parse(spec, "::: " + spec);
            } catch (Attributes.MarkerException e) {
                throw e.at(lineNumber);
            }
            return BlockStart.of(new Parser(new Div(attributes, lineNumber))).atIndex(line.length());
        }

        private static Attributes whole(String spec) {
            int end = Attributes.end(spec, 0);
            if (end != spec.length()) {
                throw new Attributes.MarkerException("expected only a {! ...} marker after :::, got \"" + spec + "\"");
            }
            return Attributes.parseMarker(spec);
        }
    }

    private static final class Parser extends AbstractBlockParser {

        private final Div div;

        Parser(Div div) {
            this.div = div;
        }

        @Override
        public Block getBlock() {
            return div;
        }

        @Override
        public boolean isContainer() {
            return true;
        }

        @Override
        public boolean canContain(Block childBlock) {
            return true;
        }

        @Override
        public BlockContinue tryContinue(ParserState state) {
            if (CLOSE.matcher(state.getLine().getContent()).matches() && innermostOpenDiv(state) == div) {
                div.closed = true;
                return BlockContinue.finished();
            }
            return BlockContinue.atIndex(state.getIndex());
        }

        /** The div closest to where parsing currently is, so a ::: closes the right one. */
        private static Div innermostOpenDiv(ParserState state) {
            for (org.commonmark.node.Node n = state.getActiveBlockParser().getBlock(); n != null; n = n.getParent()) {
                if (n instanceof Div d && !d.closed) {
                    return d;
                }
            }
            return null;
        }
    }

    private static int lineOf(SourceLine line) {
        return line.getSourceSpan() == null ? -1 : line.getSourceSpan().getLineIndex() + 1;
    }
}
