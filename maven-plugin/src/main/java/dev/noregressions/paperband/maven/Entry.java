package dev.noregressions.paperband.maven;

/**
 * One {@code <entry>} of the plugin's {@code <sequence>}, with the same fields as a manifest's
 * sequence entry. {@code where} and {@code sort} are written as YAML, exactly as in a manifest:
 *
 * <pre>
 * &lt;entry&gt;
 *   &lt;files&gt;docs/chapters/*.md&lt;/files&gt;
 *   &lt;where&gt;{ draft: { ne: true } }&lt;/where&gt;
 *   &lt;sort&gt;[page.part, page.chapter]&lt;/sort&gt;
 *   &lt;layout&gt;chapter&lt;/layout&gt;
 * &lt;/entry&gt;
 * </pre>
 */
public class Entry {

    /** One source file. */
    String file;

    /** A pattern of source files. */
    String files;

    /** The layout for these pages, overriding the files' own (or the layout of a generated page). */
    String layout;

    /** A YAML filter on front matter, as in a manifest. */
    String where;

    /** A YAML sort order, as in a manifest. */
    String sort;
}
