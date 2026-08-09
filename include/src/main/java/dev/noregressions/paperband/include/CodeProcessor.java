package dev.noregressions.paperband.include;

/**
 * {@link FragmentProcessor} that wraps the fragment in a fenced code block.
 *
 * <p>Language tag resolution:
 * <ol>
 *   <li>Explicit {@code lang=...} attribute on the directive.</li>
 *   <li>{@link Fragment#inferredLanguage()} from the provider.</li>
 *   <li>No language tag (plain ``` fence).</li>
 * </ol>
 */
public final class CodeProcessor implements FragmentProcessor {

    public static final String NAME = "code";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String process(Fragment fragment, IncludeContext ctx) {
        String lang = ctx.attributes().get("lang");
        if (lang == null || lang.isBlank()) {
            lang = fragment.inferredLanguage().orElse("");
        }
        StringBuilder sb = new StringBuilder();
        // Surround with blank lines so flexmark always sees this as a block,
        // even when the directive sat inline within text.
        sb.append("\n\n```").append(lang).append('\n');
        sb.append(fragment.content());
        if (!fragment.content().endsWith("\n")) sb.append('\n');
        sb.append("```\n\n");
        return sb.toString();
    }
}
