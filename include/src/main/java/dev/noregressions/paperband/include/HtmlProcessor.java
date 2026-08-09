package dev.noregressions.paperband.include;

/**
 * {@link FragmentProcessor} for HTML fragments. Splices the content as a raw
 * HTML block: flexmark passes raw HTML through to the rendered document
 * without re-parsing it as markdown.
 *
 * <p>The fragment is wrapped in blank lines so flexmark recognises it as a
 * block-level HTML element rather than inline HTML embedded in a paragraph.
 */
public final class HtmlProcessor implements FragmentProcessor {

    public static final String NAME = "html";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String process(Fragment fragment, IncludeContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n");
        sb.append(fragment.content());
        if (!fragment.content().endsWith("\n")) sb.append('\n');
        sb.append('\n');
        return sb.toString();
    }
}
