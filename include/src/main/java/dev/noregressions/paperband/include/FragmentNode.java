package dev.noregressions.paperband.include;

import io.pebbletemplates.pebble.extension.NodeVisitor;
import io.pebbletemplates.pebble.node.AbstractRenderableNode;
import io.pebbletemplates.pebble.node.expression.Expression;
import io.pebbletemplates.pebble.template.EvaluationContextImpl;
import io.pebbletemplates.pebble.template.PebbleTemplateImpl;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The runtime node behind the {@code {% fragment %}} tag. Evaluates its
 * reference and named-argument expressions, then delegates to the existing
 * {@link ContentProvider}/{@link FragmentProcessor} machinery exactly the way
 * the old regex-based {@code IncludePreprocessor} did — only the parsing
 * layer changed, not fragment resolution.
 *
 * <p>Writes its result directly to the template {@link Writer}, unescaped:
 * this pass runs pre-flexmark, producing markdown/HTML source to be spliced
 * into the document, not a final HTML page, so Pebble's autoescaping (which
 * only wraps {@code {{ }}} print expressions) never applies here regardless.
 */
final class FragmentNode extends AbstractRenderableNode {

    private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9]*)://(.*)$");
    private static final String DEFAULT_PROVIDER = "file";

    private final Expression<?> referenceExpr;
    private final Map<String, Expression<?>> namedArgs; // insertion order preserved
    private final Map<String, ContentProvider> providers;
    private final Map<String, FragmentProcessor> processors;
    private final Path sourceFile;
    private final Path bookRoot;
    private final Map<String, Map<String, Object>> providerConfigs;

    FragmentNode(
            int lineNumber,
            Expression<?> referenceExpr,
            Map<String, Expression<?>> namedArgs,
            Map<String, ContentProvider> providers,
            Map<String, FragmentProcessor> processors,
            Path sourceFile,
            Path bookRoot,
            Map<String, Map<String, Object>> providerConfigs) {
        super(lineNumber);
        this.referenceExpr = referenceExpr;
        this.namedArgs = namedArgs;
        this.providers = providers;
        this.processors = processors;
        this.sourceFile = sourceFile;
        this.bookRoot = bookRoot;
        this.providerConfigs = providerConfigs;
    }

    @Override
    public void render(PebbleTemplateImpl self, Writer writer, EvaluationContextImpl context) throws IOException {
        Object refValue = referenceExpr.evaluate(self, context);
        String reference = refValue == null ? "" : String.valueOf(refValue);
        if (reference.isBlank()) {
            throw new IncludeException(
                    "{% fragment %} reference must not be blank" + atLine(), sourceFile);
        }

        Map<String, String> attrs = new LinkedHashMap<>();
        String returnType = null;
        for (Map.Entry<String, Expression<?>> e : namedArgs.entrySet()) {
            Object v = e.getValue().evaluate(self, context);
            String sv = v == null ? "" : String.valueOf(v);
            if ("as".equals(e.getKey())) {
                returnType = sv;
            } else {
                attrs.put(e.getKey(), sv);
            }
        }

        String providerName = DEFAULT_PROVIDER;
        String ref = reference;
        Matcher schemeMatch = SCHEME.matcher(reference);
        if (schemeMatch.matches()) {
            providerName = schemeMatch.group(1);
            ref = schemeMatch.group(2);
        }

        ContentProvider provider = providers.get(providerName);
        if (provider == null) {
            throw new IncludeException(
                    "Unknown content provider '" + providerName + "' in {% fragment %}: "
                            + reference + " (registered: " + providers.keySet() + ")" + atLine(),
                    sourceFile);
        }

        IncludeContext ctx = new IncludeContext(
                sourceFile, bookRoot, attrs, providerConfigs.getOrDefault(providerName, Map.of()));

        Fragment fragment;
        try {
            fragment = provider.fetch(ref, ctx);
        } catch (ContentResolutionException e) {
            throw new IncludeException(
                    "Include failed in " + sourceFile + atLine() + ": " + e.getMessage(), sourceFile, e);
        }

        String processorName = returnType != null
                ? returnType
                : PebbleIncludePreprocessor.defaultProcessorFor(fragment.mediaType());
        FragmentProcessor processor = processors.get(processorName);
        if (processor == null) {
            throw new IncludeException(
                    "Unknown return type '" + processorName + "' in {% fragment %}: "
                            + reference + " (registered: " + processors.keySet() + ")" + atLine(),
                    sourceFile);
        }

        writer.write(processor.process(fragment, ctx));
    }

    private String atLine() {
        return " (line " + getLineNumber() + ")";
    }

    @Override
    public void accept(NodeVisitor visitor) {
        visitor.visit(this);
    }
}
