package dev.noregressions.paperband.include;

/**
 * {@link FragmentProcessor} for fragments that should be rendered as plain
 * text inside a fenced block with no language tag. The content is not parsed
 * as markdown.
 *
 * <p>Used as the default when the fragment's media type isn't markdown, HTML,
 * or a recognised programming-language extension.
 */
public final class TextProcessor implements FragmentProcessor {
    /** Creates a new text processor. */
    public TextProcessor() {}

    public static final String NAME = "text";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String process(Fragment fragment, IncludeContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n```\n");
        sb.append(fragment.content());
        if (!fragment.content().endsWith("\n")) sb.append('\n');
        sb.append("```\n\n");
        return sb.toString();
    }
}
