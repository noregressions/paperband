package dev.noregressions.paperband.maven;

import dev.noregressions.paperband.render.Margins;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What every Paperband goal shares: the parameters that describe the build
 * rather than the artefact, and the resolution helpers around them.
 *
 * <p>Goals that don't render a PDF ({@code renderers}, {@code themes},
 * {@code scan}) simply leave the geometry parameters alone.
 */
public abstract class AbstractPaperbandMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    protected MavenProject project;

    /** Build target, e.g. {@code pdf-a4}, {@code pdf-6x9}, {@code web}. */
    @Parameter(property = "paperband.target", defaultValue = "pdf-a4")
    protected String target;

    /** Page size slug, e.g. {@code a4}, {@code letter}, {@code 6x9}. */
    @Parameter(property = "paperband.pageSize", defaultValue = "a4")
    protected String pageSize;

    /**
     * Page margins, as a CSS-style shorthand: one to four lengths, optionally
     * with a unit ({@code mm} by default, or {@code cm}/{@code in}/{@code pt}).
     *
     * <pre>
     * &lt;margins&gt;0&lt;/margins&gt;              full bleed — see below
     * &lt;margins&gt;18mm&lt;/margins&gt;           18mm on every edge
     * &lt;margins&gt;20mm 15mm&lt;/margins&gt;      vertical, horizontal
     * &lt;margins&gt;20 15 25 15&lt;/margins&gt;    top, right, bottom, left
     * </pre>
     *
     * <p>Unset, the page size preset's own margins apply (20mm for A4, zero
     * for A5). Like {@link #pageSize}, this seeds the <em>base</em> geometry,
     * so a {@code vars.page.margins} block in the book's yaml still wins.
     *
     * <p>{@code 0} is what a theme whose ground is the paper needs: Chromium
     * paints nothing into a PDF page margin, so any margin shows as a white
     * border around every page, and zero is the only way a coloured ground
     * reaches the trim edge. The bundled full-bleed themes supply their own
     * insets in that case — see the Themes guide.
     */
    @Parameter(property = "paperband.margins")
    protected String margins;

    /** Renderer name. Use the {@code renderers} goal to list what's available. */
    @Parameter(property = "paperband.renderer", defaultValue = "playwright")
    protected String renderer;

    /** Named theme to apply. Overrides any {@code theme:} declared in the book's {@code paperband.yaml}. */
    // alias: <theme>, as documented and as the property is named. Without it
    // Maven silently ignores the documented element name.
    @Parameter(property = "paperband.theme", alias = "theme")
    protected String themeName;

    /** User theme directory, checked before the built-ins. */
    @Parameter(property = "paperband.themeDir")
    protected File themeDir;

    /**
     * Permit {@code &#123;&#123;#include&#125;&#125;} directives to read files
     * under these directories, even though they sit outside the book root. Off
     * by default; name only directories you trust, since any card can then read
     * anything beneath them.
     */
    @Parameter(property = "paperband.externalIncludeDirs")
    protected List<File> externalIncludeDirs;

    /** Permit includes to read these specific files. Narrower than {@link #externalIncludeDirs}. */
    @Parameter(property = "paperband.externalIncludeFiles")
    protected List<File> externalIncludeFiles;

    /**
     * Stylesheets this build contributes, inlined <em>after</em> the theme — the
     * strongest layer of the cascade, and the place for the colours and fonts
     * of a book that declares its structure in the POM.
     *
     * <pre>
     * &lt;stylesheets&gt;
     *   &lt;stylesheet&gt;src/book/tokens.css&lt;/stylesheet&gt;
     *   &lt;stylesheet&gt;src/book/print.css&lt;/stylesheet&gt;
     * &lt;/stylesheets&gt;
     * </pre>
     *
     * <p>Paths, not CSS: the stylesheets stay in {@code .css} files where they
     * can be edited and reviewed as CSS. Applying them after the theme is what
     * makes {@code <theme>blueprint</theme>} plus a stylesheet mean "that theme,
     * with my corrections" — the book's own {@code css:} chain sits below the
     * theme and can't override it.
     */
    @Parameter(property = "paperband.stylesheets")
    protected List<File> stylesheets;

    /** Skip this goal without failing the build. */
    @Parameter(property = "paperband.skip", defaultValue = "false")
    protected boolean skip;

    /** Log and report whether {@code paperband.skip} turned this goal off. */
    protected boolean skipped(String goal) {
        if (skip) {
            getLog().info("paperband.skip=true — skipping " + goal + ".");
            return true;
        }
        return false;
    }

    /** The module basedir, or the working directory outside a project. */
    protected Path basedir() {
        return project == null ? Path.of("") : project.getBasedir().toPath();
    }

    /** Resolve a configured file against the module basedir when it's relative. */
    protected Path resolve(File f) {
        Path p = f.toPath();
        return p.isAbsolute() ? p : basedir().resolve(p);
    }

    /** The declared stylesheets as absolute paths, in declaration order. */
    protected List<Path> stylesheetPaths() {
        if (stylesheets == null) return List.of();
        List<Path> out = new ArrayList<>(stylesheets.size());
        for (File f : stylesheets) {
            if (f != null) out.add(resolve(f));
        }
        return out;
    }

    protected Path themeDirPath() {
        return themeDir == null ? null : themeDir.toPath();
    }

    /**
     * The {@code <margins>} shorthand as real {@link Margins}, or null when the
     * POM declares none.
     *
     * @throws MojoExecutionException if the shorthand is malformed — a typo in
     *         page geometry should fail the build, not silently render at the
     *         preset's margins
     */
    protected Margins resolveMargins() throws MojoExecutionException {
        try {
            return Margins.parse(margins);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException("<margins>: " + e.getMessage(), e);
        }
    }

    /**
     * Include-provider config derived solely from the build's own parameters.
     * The trust decision to let includes escape the book root belongs to whoever
     * runs the build, not to the book being built — so this is never populated
     * from the book's own {@code paperband.yaml}, and it names the specific
     * directories and files trusted rather than flipping a blanket switch.
     */
    protected Map<String, Map<String, Object>> includeProviderConfig() {
        List<String> dirs = absoluteStrings(externalIncludeDirs);
        List<String> files = absoluteStrings(externalIncludeFiles);
        if (dirs.isEmpty() && files.isEmpty()) return Map.of();
        Map<String, Object> fileCfg = new HashMap<>();
        if (!dirs.isEmpty()) fileCfg.put("external_roots", dirs);
        if (!files.isEmpty()) fileCfg.put("external_files", files);
        return Map.of("file", Map.copyOf(fileCfg));
    }

    private List<String> absoluteStrings(List<File> files) {
        if (files == null) return List.of();
        List<String> out = new ArrayList<>(files.size());
        for (File f : files) {
            if (f != null) out.add(resolve(f).toAbsolutePath().normalize().toString());
        }
        return out;
    }

    /**
     * Run a {@link BookBuild}, mapping its failures onto Maven's two kinds:
     * a content problem fails the build, anything else is an execution error.
     */
    protected void run(BookBuild build) throws MojoExecutionException, MojoFailureException {
        try {
            build.run();
        } catch (MojoExecutionException | MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Paperband build failed: " + e.getMessage(), e);
        }
    }
}
