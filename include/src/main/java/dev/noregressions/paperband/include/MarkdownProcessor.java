package dev.noregressions.paperband.include;

/**
 * {@link FragmentProcessor} that splices the fragment into the parent document
 * verbatim, letting the parent flexmark pass parse it as markdown.
 *
 * <p>Use for {@code .md} fragments shared across cards (boilerplate intros,
 * common warnings, glossary chunks).
 */
public final class MarkdownProcessor implements FragmentProcessor {

    public static final String NAME = "markdown";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String process(Fragment fragment, IncludeContext ctx) {
        // Surround with blank lines so block-level constructs at the edges
        // (headings, lists) still parse correctly when inserted mid-paragraph.
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n");
        sb.append(fragment.content());
        if (!fragment.content().endsWith("\n")) sb.append('\n');
        sb.append('\n');
        return sb.toString();
    }
}
