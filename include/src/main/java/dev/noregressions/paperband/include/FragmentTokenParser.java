package dev.noregressions.paperband.include;

import io.pebbletemplates.pebble.lexer.Token;
import io.pebbletemplates.pebble.lexer.TokenStream;
import io.pebbletemplates.pebble.node.RenderableNode;
import io.pebbletemplates.pebble.node.expression.Expression;
import io.pebbletemplates.pebble.parser.Parser;
import io.pebbletemplates.pebble.tokenParser.TokenParser;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses the {@code {% fragment "path:anchor" [, name=expr]* %}} tag.
 *
 * <p>Grammar: {@code fragment} followed by one positional expression (the
 * reference) and zero or more {@code , name = expression} pairs. Both the
 * reference and every named value are real Pebble expressions — string
 * literals, variables, concatenation ({@code ~}) — evaluated at render time,
 * not the bare unquoted tokens the old {@code {{#include ...}}} regex syntax
 * required. {@code as = <type>} plays the role the old directive's
 * {@code as <type>} clause did; every other name is forwarded as a provider/
 * processor attribute (e.g. {@code lang="python"}, matching the old
 * {@code key=value} clauses).
 *
 * <p>Replaces the old hand-rolled {@code DirectiveParser}: token boundaries,
 * quoting, and line numbers all come from Pebble's real lexer instead of a
 * regex plus manual whitespace splitting.
 */
final class FragmentTokenParser implements TokenParser {

    private final Map<String, ContentProvider> providers;
    private final Map<String, FragmentProcessor> processors;
    private final Path sourceFile;
    private final Path bookRoot;
    private final Map<String, Map<String, Object>> providerConfigs;

    FragmentTokenParser(
            Map<String, ContentProvider> providers,
            Map<String, FragmentProcessor> processors,
            Path sourceFile,
            Path bookRoot,
            Map<String, Map<String, Object>> providerConfigs) {
        this.providers = providers;
        this.processors = processors;
        this.sourceFile = sourceFile;
        this.bookRoot = bookRoot;
        this.providerConfigs = providerConfigs;
    }

    @Override
    public String getTag() {
        return "fragment";
    }

    @Override
    // ANCHOR: fragment-tag-grammar
    public RenderableNode parse(Token token, Parser parser) {
        int lineNumber = token.getLineNumber();
        TokenStream stream = parser.getStream();
        stream.next(); // skip the 'fragment' tag name token

        Expression<?> reference = parser.getExpressionParser().parseExpression();

        Map<String, Expression<?>> namedArgs = new LinkedHashMap<>();
        while (stream.current().test(Token.Type.PUNCTUATION, ",")) {
            stream.next(); // skip ','
            String name = stream.expect(Token.Type.NAME).getValue();
            stream.expect(Token.Type.PUNCTUATION, "=");
            Expression<?> value = parser.getExpressionParser().parseExpression();
            namedArgs.put(name, value);
        }

        stream.expect(Token.Type.EXECUTE_END);

        return new FragmentNode(
                lineNumber, reference, namedArgs, providers, processors, sourceFile, bookRoot, providerConfigs);
    }
    // ANCHOR_END: fragment-tag-grammar
}
