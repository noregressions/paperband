package dev.noregressions.paperband.maven;

import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.Parameter;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Strict POM configuration checking.
 *
 * <p>The gap this closes is narrow but expensive: Maven <em>warns</em> about an
 * element no parameter matches and carries on, so the element looks configured
 * and does nothing. Nested elements and unparseable numbers were already hard
 * errors from Maven's own configurator; unknown and misplaced top-level
 * elements, and silently-converted booleans, were not.
 */
@DisplayName("POM configuration validation")
class PomValidationTest {

    private static final String GROUP = "dev.noregressions.paperband";
    private static final String ARTIFACT = "paperband-maven-plugin";

    /** A plugin descriptor with two goals: build (with {@code <book>}) and site (with {@code <clean>}). */
    private static PluginDescriptor descriptor() throws Exception {
        PluginDescriptor plugin = new PluginDescriptor();
        plugin.setGroupId(GROUP);
        plugin.setArtifactId(ARTIFACT);
        plugin.addMojo(mojo(plugin, "build", BuildMojo.class, "book", "output", "maxPagesPerCard"));
        plugin.addMojo(mojo(plugin, "site", SiteMojo.class, "book", "outputDirectory", "clean"));
        return plugin;
    }

    private static MojoDescriptor mojo(PluginDescriptor plugin, String goal, Class<?> impl,
                                       String... parameters) throws Exception {
        MojoDescriptor mojo = new MojoDescriptor();
        mojo.setGoal(goal);
        mojo.setPluginDescriptor(plugin);
        mojo.setImplementation(impl.getName());
        for (String name : parameters) {
            Parameter p = new Parameter();
            p.setName(name);
            mojo.addParameter(p);
        }
        return mojo;
    }

    /** Parse a {@code <configuration>} fragment. */
    private static Xpp3Dom dom(String xml) throws Exception {
        return Xpp3DomBuilderCompat.build(xml);
    }

    /** Run the check for {@code goal}, with the given plugin-level and execution-level config. */
    private static void check(String goal, String pluginConfig, String executionId,
                              String executionGoal, String executionConfig) throws Exception {
        PluginDescriptor descriptor = descriptor();
        Plugin plugin = new Plugin();
        plugin.setGroupId(GROUP);
        plugin.setArtifactId(ARTIFACT);
        if (pluginConfig != null) plugin.setConfiguration(dom(pluginConfig));
        if (executionConfig != null) {
            PluginExecution execution = new PluginExecution();
            execution.setId(executionId);
            execution.setGoals(List.of(executionGoal));
            execution.setConfiguration(dom(executionConfig));
            plugin.addExecution(execution);
        }
        Build build = new Build();
        build.addPlugin(plugin);
        MavenProject project = new MavenProject();
        project.setBuild(build);

        PomValidation.check(new MojoExecution(descriptor.getMojo(goal)), project);
    }

    private static String message(Executable body) {
        MojoExecutionException e = assertThrows(MojoExecutionException.class, body::run);
        return e.getMessage();
    }

    private interface Executable {
        void run() throws Exception;
    }

    @Nested
    @DisplayName("Unknown elements")
    class Unknown {

        @Test
        void a_var_written_as_a_parameter_says_where_it_belongs() {
            // The mistake that motivated this: <sidebar> on the site execution
            // is a book var, silently ignored by Maven with only a warning.
            String msg = message(() -> check("build", null,
                    "site-exec", "site", "<configuration><sidebar>true</sidebar></configuration>"));

            assertTrue(msg.contains("<sidebar> is not a Paperband plugin parameter"), msg);
            assertTrue(msg.contains("<book><vars><sidebar>"), msg);
            assertTrue(msg.contains("site-exec"), "names the block at fault: " + msg);
        }

        @Test
        void a_misspelling_suggests_the_real_parameter() {
            String msg = message(() -> check("build",
                    "<configuration><bok></bok></configuration>", null, null, null));

            assertTrue(msg.contains("Did you mean <book>?"), msg);
        }

        @Test
        void an_unrecognisable_name_lists_what_is_valid() {
            String msg = message(() -> check("build",
                    "<configuration><wibble>x</wibble></configuration>", null, null, null));

            assertTrue(msg.contains("Valid here:"), msg);
            assertTrue(msg.contains("book"), msg);
        }
    }

    @Nested
    @DisplayName("Misplaced elements")
    class Misplaced {

        @Test
        void another_goals_parameter_on_an_execution_names_that_goal() {
            String msg = message(() -> check("build", null, "pdf", "build",
                    "<configuration><clean>true</clean></configuration>"));

            assertTrue(msg.contains("<clean>"), msg);
            assertTrue(msg.contains("site goal"), msg);
            assertTrue(msg.contains("does not configure"), msg);
        }

        @Test
        void plugin_level_config_may_carry_any_goals_parameter() throws Exception {
            // Plugin-level configuration is shared by every goal, so <clean>
            // there is legitimate even while the build goal runs. Rejecting it
            // would fail correct builds.
            assertDoesNotThrow(() -> check("build",
                    "<configuration><clean>true</clean><book><title>T</title></book></configuration>",
                    null, null, null));
        }
    }

    @Nested
    @DisplayName("Values")
    class Values {

        @Test
        void a_non_boolean_boolean_is_rejected() {
            // Plexus converts 'yes' to false without complaining, so a cover
            // that should fill the sheet quietly doesn't.
            String msg = message(() -> check("build",
                    "<configuration><book><cover><fullPage>yes</fullPage></cover></book></configuration>",
                    null, null, null));

            assertTrue(msg.contains("must be true or false"), msg);
            assertTrue(msg.contains("<book><cover><fullPage>"), "full path in the message: " + msg);
        }

        @Test
        void true_and_false_pass_in_either_case() {
            assertDoesNotThrow(() -> check("build",
                    "<configuration><book><cover><fullPage>TRUE</fullPage></cover></book></configuration>",
                    null, null, null));
        }

        @Test
        void an_unresolved_property_is_left_alone() throws Exception {
            // Not ours to judge — the value arrives after interpolation.
            assertDoesNotThrow(() -> check("build",
                    "<configuration><book><cover><fullPage>${coverFullPage}</fullPage></cover></book>"
                            + "</configuration>", null, null, null));
        }
    }

    @Nested
    @DisplayName("Valid configuration")
    class Valid {

        @Test
        void a_realistic_book_declaration_passes() {
            assertDoesNotThrow(() -> check("build",
                    "<configuration>"
                            + "<book>"
                            + "  <title>A Book</title>"
                            + "  <author>Ada Lovelace</author>"
                            + "  <cover><image>c.png</image><fullPage>true</fullPage></cover>"
                            + "  <header><template>layouts/h.html</template></header>"
                            + "  <vars><subtitle>Sub</subtitle><sidebar>true</sidebar></vars>"
                            + "  <axes><axis><name>tier</name>"
                            + "    <values><value><id>1</id><label>One</label></value></values>"
                            + "  </axis></axes>"
                            + "</book>"
                            + "</configuration>",
                    "pdf", "build", "<configuration><output>out.pdf</output></configuration>"));
        }

        @Test
        void arbitrary_var_names_are_allowed() {
            // <vars> is a map: its children are keys the author invents.
            assertDoesNotThrow(() -> check("build",
                    "<configuration><book><vars><anythingAtAll>x</anythingAtAll></vars></book>"
                            + "</configuration>", null, null, null));
        }

        @Test
        void no_declaration_for_this_plugin_is_not_an_error() {
            assertDoesNotThrow(() -> {
                MavenProject project = new MavenProject();
                project.setBuild(new Build());
                PomValidation.check(new MojoExecution(descriptor().getMojo("build")), project);
            });
        }

        @Test
        void no_project_is_not_an_error() {
            // The scan goal runs with requiresProject = false.
            assertDoesNotThrow(() ->
                    PomValidation.check(new MojoExecution(descriptor().getMojo("build")), null));
        }
    }

    /** Tiny wrapper so the test reads as XML rather than DOM building. */
    static final class Xpp3DomBuilderCompat {
        static Xpp3Dom build(String xml) throws Exception {
            return org.codehaus.plexus.util.xml.Xpp3DomBuilder.build(
                    new java.io.StringReader(xml));
        }
    }
}
