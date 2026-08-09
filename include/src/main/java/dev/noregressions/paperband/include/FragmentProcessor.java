package dev.noregressions.paperband.include;

/**
 * Turns a {@link Fragment} into markdown source ready to splice back into the
 * parent document, before flexmark sees it.
 *
 * <p>One implementation per return type. Built-ins:
 * <ul>
 *   <li>{@code code} — wraps in a fenced code block, language from
 *       {@link Fragment#inferredLanguage()} or the {@code lang} attribute</li>
 *   <li>{@code markdown} — splices the fragment in verbatim, parsed as markdown
 *       by the parent flexmark pass</li>
 *   <li>{@code html} — splices as raw HTML; flexmark passes raw HTML through
 *       to the rendered document</li>
 *   <li>{@code text} — wraps in a fenced block with no language tag, no
 *       markdown parsing of the content</li>
 * </ul>
 *
 * <p>The default return type is inferred from the fragment's media type when
 * the directive doesn't say {@code as <type>}: markdown for {@code text/markdown},
 * html for {@code text/html}, code for everything else recognisable, text as
 * the fallback.
 */
public interface FragmentProcessor {

    /**
     * Identifier matching the {@code as <name>} keyword in directives:
     * {@code code}, {@code markdown}, {@code html}, {@code text}, etc.
     */
    String name();

    /**
     * Render {@code fragment} as markdown source ready to be substituted back
     * into the document. The returned string will be inserted at the position
     * where the directive appeared.
     *
     * @param fragment  the content fetched by a {@link ContentProvider}
     * @param ctx       directive context (attributes, source location);
     *                  processors typically consult {@code lang}, {@code title},
     *                  and similar keys
     */
    String process(Fragment fragment, IncludeContext ctx);
}
