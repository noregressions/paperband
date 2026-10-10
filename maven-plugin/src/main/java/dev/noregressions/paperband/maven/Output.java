package dev.noregressions.paperband.maven;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * One {@code <output>} of the plugin's {@code <outputs>}, with the same fields as a manifest's
 * output. {@code page} is written as YAML, exactly as in a manifest:
 *
 * <pre>
 * &lt;output&gt;
 *   &lt;name&gt;handbook&lt;/name&gt;
 *   &lt;format&gt;paged-html&lt;/format&gt;
 *   &lt;file&gt;${project.build.directory}/handbook.html&lt;/file&gt;
 *   &lt;layout&gt;print-page&lt;/layout&gt;
 *   &lt;page&gt;{ size: A4, margin: 15mm }&lt;/page&gt;
 * &lt;/output&gt;
 * </pre>
 */
public class Output {

    /** Letters, digits and -; unique. */
    String name;

    /** web, paged-html or pdf. */
    String format;

    /** Where to write it. */
    File file;

    /** The layout for pages from source files whose sequence entry names none. */
    String layout;

    /** Layout names to replace on every page, e.g. {@code <layouts><hero>hero-print</hero></layouts>}. */
    Map<String, String> layouts;

    /** This output's default page, as YAML. */
    String page;

    /** The variant to show where content offers several: full, short or shortest. */
    String prefer;

    /** For web: everything in one HTML file, instead of a file per page. */
    Boolean single;

    /** For web: side (the contents beside every page, the default), top (a bar of dropdowns) or none. */
    String nav;

    /** For web, with side or top navigation: the page shown as the navigation, by file or layout name. */
    String contents;

    /** This output's own pages (see {@link Entry}); default: the build's sequence. */
    List<Entry> sequence;
}
