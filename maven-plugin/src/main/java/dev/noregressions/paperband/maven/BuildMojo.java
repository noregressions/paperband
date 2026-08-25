package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.render.Margins;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Builds a PDF from a single Paperband markdown card, or an entire book
 * (a directory tree of cards, or a book declared in the POM).
 *
 * <p>Example:
 * <pre>{@code
 * <plugin>
 *   <groupId>dev.noregressions.paperband</groupId>
 *   <artifactId>paperband-maven-plugin</artifactId>
 *   <version>0.1.0</version>
 *   <executions>
 *     <execution>
 *       <goals><goal>build</goal></goals>
 *       <configuration>
 *         <input>${project.basedir}/guide</input>
 *         <output>${project.build.directory}/guide.pdf</output>
 *       </configuration>
 *     </execution>
 *   </executions>
 * </plugin>
 * }</pre>
 * or invoked directly: {@code mvn paperband:build -Dpaperband.input=guide -Dpaperband.output=guide.pdf}
 *
 * <p>In place of {@code <input>}, a book's structure can be declared in the
 * POM itself and its cards selected by glob — see {@link BookLayout}:
 *
 * <pre>
 * &lt;configuration&gt;
 *   &lt;output&gt;${project.build.directory}/traces.pdf&lt;/output&gt;
 *   &lt;book&gt;
 *     &lt;sections&gt;
 *       &lt;section&gt;
 *         &lt;title&gt;Execution Traces&lt;/title&gt;
 *         &lt;includes&gt;&lt;include&gt;services/&#42;/TRACE.md&lt;/include&gt;&lt;/includes&gt;
 *       &lt;/section&gt;
 *     &lt;/sections&gt;
 *   &lt;/book&gt;
 * &lt;/configuration&gt;
 * </pre>
 *
 * <p>The pipeline itself lives in {@link BookBuild}, which the {@code publish}
 * goal drives too — one build path for a plain build and an edition build.
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.PROCESS_RESOURCES, threadSafe = true)
public class BuildMojo extends AbstractPaperbandMojo {

    /**
     * Markdown file (single card) or directory (book). Relative paths resolve
     * against the module's basedir. Mutually exclusive with {@link #book}.
     */
    @Parameter(property = "paperband.input")
    private java.io.File input;

    /**
     * A book whose structure is declared in the POM and whose cards are
     * selected by glob, instead of inferred from the directory tree. Mutually
     * exclusive with {@link #input} — see {@link BookLayout}.
     */
    @Parameter
    private BookLayout book;

    /** Output PDF file. */
    @Parameter(property = "paperband.output", required = true)
    private java.io.File output;

    /** Layout template name override. Defaults to the context layout, or 'card'/'book' if unset. */
    // alias: the POM element every doc and example uses is <layout>, matching
    // the -Dpaperband.layout property; without it Maven silently ignores
    // <layout> (it warns, but a warning in a resource-phase build is easy to
    // miss) and the build runs with no override at all.
    @Parameter(property = "paperband.layout", alias = "layout")
    private String layoutOverride;

    /**
     * Also write the rendered HTML here, before it reaches the renderer — the
     * debugging view of what layout produced. A book's copy gets a
     * {@code <base href>} stamped in so its relative assets still resolve when
     * opened in a browser.
     */
    @Parameter(property = "paperband.emitHtml")
    private java.io.File emitHtml;

    /** Print a per-anchor page-span table after rendering. */
    @Parameter(property = "paperband.reportPages", defaultValue = "false")
    private boolean reportPages;

    /**
     * Fail the build if any card renders to more pages than this. A card's own
     * {@code max_pages} frontmatter wins over it; {@code vars.maxPagesPerCard}
     * in the book's yaml is the fallback when this isn't set.
     */
    @Parameter(property = "paperband.maxPagesPerCard")
    private Integer maxPagesPerCard;

    /**
     * Keep only the cards whose frontmatter field equals this value, as
     * {@code field=value} — e.g. {@code tier=1}. The folder-cascaded vars entry
     * is the fallback when the card's own frontmatter doesn't carry the field.
     * Book builds only.
     */
    @Parameter(property = "paperband.select")
    private String select;

    /**
     * Stamp this text across every page after rendering (e.g. {@code DRAFT}).
     * Overrides a {@code vars.watermark} declared in the book's yaml.
     */
    @Parameter(property = "paperband.watermark")
    private String watermarkText;

    /** Watermark fill colour as {@code #RRGGBB}. */
    @Parameter(property = "paperband.watermarkColor")
    private String watermarkColor;

    /** Watermark fill alpha, 0 to 1. */
    @Parameter(property = "paperband.watermarkOpacity")
    private Float watermarkOpacity;

    /** Watermark rotation in degrees. */
    @Parameter(property = "paperband.watermarkAngle")
    private Float watermarkAngle;

    /** Watermark font size in points. */
    @Parameter(property = "paperband.watermarkFontSize")
    private Integer watermarkFontSize;

    @Override
    public void execute() throws MojoExecutionException, org.apache.maven.plugin.MojoFailureException {
        if (skipped("build")) return;

        checkBookDeclaration(book);
        boolean hasInput = input != null;
        boolean hasBook = book != null;
        if (hasInput && hasBook) {
            throw new MojoExecutionException(
                    "Configure either <input> or <book>, not both — <book> declares the structure "
                            + "the walker would otherwise infer from <input>'s directory tree.");
        }
        if (!hasInput && !hasBook) {
            throw new MojoExecutionException("Configure <input> (or <book>) — nothing to build.");
        }

        BookBuild build = new BookBuild(getLog());
        build.output = resolve(output);
        build.target = target;
        build.pageSize = pageSize;
        build.margins = parsedMargins();
        build.marginsLabel = margins;
        build.rendererName = renderer;
        build.themeName = themeName;
        build.themeDir = themeDirPath();
        build.layoutOverride = layoutOverride;
        build.emitHtml = emitHtml == null ? null : resolve(emitHtml);
        build.includeProviderConfig = includeProviderConfig();
        build.watermarkText = watermarkText;
        build.watermarkColor = watermarkColor;
        build.watermarkOpacity = watermarkOpacity;
        build.watermarkAngle = watermarkAngle;
        build.watermarkFontSize = watermarkFontSize;
        build.reportPages = reportPages;
        build.maxPagesPerCard = maxPagesPerCard;
        build.selectClauses = parseSelect();
        build.stylesheets = stylesheetPaths();

        if (hasBook) {
            Path root = book.getRoot() != null ? resolve(book.getRoot()) : basedir();
            // Pinning the root is what lets a book have no paperband.yaml: the
            // loader stops inferring one from the first card's parent directory.
            build.declaredRoot = root;
            build.bookDeclaration = book;
            if (book.declaresCardSelection()) {
                try {
                    build.plan = new BookBuild.PlannedBook(root, book.toSpecs(), book.tocAfterSpec(),
                            book.pageMarkers());
                } catch (IllegalArgumentException e) {
                    throw new MojoExecutionException(e.getMessage(), e);
                }
            } else {
                // Config declared, structure still from the tree.
                build.input = root;
            }
        } else {
            build.input = resolve(input);
        }

        run(build);
    }

    /** {@code field=value} as a single AND-ed clause, or null when unset. */
    private Map<String, String> parseSelect() throws MojoExecutionException {
        if (select == null || select.isBlank()) return null;
        int eq = select.indexOf('=');
        if (eq <= 0 || eq == select.length() - 1) {
            throw new MojoExecutionException("<select>: expected field=value, got: " + select);
        }
        return Map.of(select.substring(0, eq).trim(), select.substring(eq + 1).trim());
    }

    /** Exposed for {@link Margins} parsing errors to surface as build failures. */
    private Margins parsedMargins() throws MojoExecutionException {
        return resolveMargins();
    }
}
