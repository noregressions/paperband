package dev.noregressions.paperband.maven;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rejects POM configuration Paperband doesn't understand.
 *
 * <p>Maven's own reaction to an element no parameter matches is a
 * {@code [WARNING] Parameter 'x' is unknown} line and carrying on. In a build
 * that prints hundreds of lines that is indistinguishable from silence: the
 * element looks configured, nothing reads it, and the symptom shows up later as
 * "the setting didn't work". A misspelled or misplaced element is a mistake, so
 * it fails the build and says what to write instead.
 *
 * <h2>Two strictness levels, deliberately</h2>
 *
 * <p>Top-level elements are checked against <em>every</em> Paperband goal, not
 * just the one running. Plugin-level {@code <configuration>} is shared by all
 * of them — a {@code <book>} declared once for {@code build} and {@code site}
 * is also handed to {@code renderers}, which has no such parameter — so
 * rejecting per-goal would fail builds that are perfectly correct. An element
 * no goal at all knows is a genuine typo.
 *
 * <p>Nested elements have no such ambiguity: {@code <book>}, {@code <cover>},
 * {@code <axis>} and friends are Paperband's own types, so their children are
 * checked strictly against exactly that type.
 *
 * <h2>Values</h2>
 *
 * <p>Booleans and numbers are checked too, because Plexus converts silently:
 * {@code <fullPage>yes</fullPage>} becomes {@code false}, not an error, and a
 * cover that should fill the sheet quietly doesn't.
 */
final class PomValidation {

    private PomValidation() {}

    /** The package Paperband's own configuration types live in. */
    private static final String OWN_PACKAGE = PomValidation.class.getPackageName();

    /**
     * Names that are book <em>vars</em> rather than plugin parameters. They read
     * like settings and get written as top-level elements, where they are
     * silently ignored — the mistake that motivated this whole class.
     */
    private static final Set<String> KNOWN_VARS = Set.of(
            "toc", "subtitle", "series", "strapline", "measure", "indexStop");

    /**
     * Vars that a typed element owns now, and where each one moved to. They read
     * like vars and used to be vars, so a POM keeps declaring them inside
     * {@code <vars>}, where they still parse — a var block takes any key — and
     * still do nothing: only the yaml loader honours the old spelling, and a
     * book declared in the POM never reaches that fallback. Silently ignored
     * configuration is exactly what this class exists to catch, so these are an
     * error wherever they appear rather than a setting that looks live.
     */
    private static final Map<String, String> RELOCATED_VARS = Map.of(
            "sidebar", "<sidebar/>",
            "sidebar_collapsed", "<sidebar><collapsed>true</collapsed></sidebar>",
            "sidebar_sections_collapsed",
            "<sidebar><sectionsCollapsed>false</sectionsCollapsed></sidebar>");

    /**
     * Validate the POM's configuration for this plugin.
     *
     * <p>Reads the <em>raw</em> POM rather than {@link MojoExecution#getConfiguration()}.
     * By the time Maven builds a MojoExecution it has already dropped every
     * element no parameter matched — that is precisely the silent failure this
     * class exists to catch, so the filtered view cannot see it. The raw plugin
     * configuration still has it.
     *
     * @param exec    the running mojo execution, for the plugin's own coordinates
     * @param project the project whose POM to read; null outside a project
     * @throws MojoExecutionException if the POM declares something unknown
     */
    static void check(MojoExecution exec, MavenProject project) throws MojoExecutionException {
        if (exec == null || project == null) return;
        MojoDescriptor descriptor = exec.getMojoDescriptor();
        PluginDescriptor plugin = descriptor == null ? null : descriptor.getPluginDescriptor();
        if (plugin == null) return;

        Plugin declared = null;
        for (Plugin p : project.getBuildPlugins()) {
            if (plugin.getArtifactId().equals(p.getArtifactId())
                    && plugin.getGroupId().equals(p.getGroupId())) {
                declared = p;
                break;
            }
        }
        if (declared == null) return;

        // Plugin-level <configuration> is shared by every goal, including ones
        // this build never runs, so it is checked against the union.
        Set<String> anyGoal = new LinkedHashSet<>();
        for (MojoDescriptor m : plugin.getMojos()) anyGoal.addAll(parametersOf(m).keySet());
        checkBlock(declared.getConfiguration(), plugin, allGoals(plugin), anyGoal,
                "<configuration>");

        // An execution's <configuration> only ever reaches its own goals, so it
        // is checked against exactly those — an element another goal would
        // accept is still a mistake here, and gets told where it belongs.
        for (PluginExecution execution : declared.getExecutions()) {
            List<MojoDescriptor> goals = new ArrayList<>();
            for (String goal : execution.getGoals()) {
                MojoDescriptor m = plugin.getMojo(goal);
                if (m != null) goals.add(m);
            }
            if (goals.isEmpty()) continue;
            Set<String> known = new LinkedHashSet<>();
            for (MojoDescriptor m : goals) known.addAll(parametersOf(m).keySet());
            checkBlock(execution.getConfiguration(), plugin, goals, known, anyGoal,
                    "execution '" + execution.getId() + "' <configuration>");
        }
    }

    private static List<MojoDescriptor> allGoals(PluginDescriptor plugin) {
        return new ArrayList<>(plugin.getMojos());
    }

    private static void checkBlock(Object config, PluginDescriptor plugin,
                                   List<MojoDescriptor> goals, Set<String> accepted, String where)
            throws MojoExecutionException {
        checkBlock(config, plugin, goals, accepted, accepted, where);
    }

    /**
     * Check one {@code <configuration>} block.
     *
     * @param accepted names legal in this block
     * @param anyGoal  names legal for the plugin somewhere, for a better message
     */
    private static void checkBlock(Object config, PluginDescriptor plugin,
                                   List<MojoDescriptor> goals, Set<String> accepted,
                                   Set<String> anyGoal, String where)
            throws MojoExecutionException {
        if (!(config instanceof Xpp3Dom dom)) return;
        for (Xpp3Dom child : dom.getChildren()) {
            String name = child.getName();
            if (!accepted.contains(name)) {
                throw new MojoExecutionException(unknownTopLevel(name, where, accepted, anyGoal,
                        plugin));
            }
            // Recurse into the first goal that declares it — the field type is
            // the same wherever a shared parameter appears.
            Field field = fieldFor(name, goals);
            if (field != null) {
                checkNode(child, field.getType(), elementType(field), "<" + name + ">");
            }
        }
    }

    /** The field a parameter name binds to, from the first goal that declares it. */
    private static Field fieldFor(String name, List<MojoDescriptor> goals) {
        for (MojoDescriptor m : goals) {
            String parameter = parametersOf(m).get(name);
            if (parameter == null) continue;
            try {
                Class<?> impl = Class.forName(m.getImplementation(), false,
                        PomValidation.class.getClassLoader());
                Field f = findField(impl, parameter);
                if (f != null) return f;
            } catch (ClassNotFoundException | LinkageError ignored) {
                // A goal we can't load is a goal we can't check nested types for.
            }
        }
        return null;
    }

    /** Element name (parameter name, plus any alias) to parameter name, for one goal. */
    private static Map<String, String> parametersOf(MojoDescriptor descriptor) {
        Map<String, String> names = new java.util.LinkedHashMap<>();
        if (descriptor.getParameters() == null) return names;
        for (org.apache.maven.plugin.descriptor.Parameter p : descriptor.getParameters()) {
            names.put(p.getName(), p.getName());
            if (p.getAlias() != null && !p.getAlias().isEmpty()) names.put(p.getAlias(), p.getName());
        }
        return names;
    }

    // ---- recursive element checking ----

    /**
     * Check one configuration element against the type it is bound to.
     *
     * @param node    the element
     * @param type    the declared field type
     * @param element for a collection, its element type; otherwise null
     * @param path    human-readable path for messages, e.g. {@code <book><cover>}
     */
    private static void checkNode(Xpp3Dom node, Class<?> type, Class<?> element, String path)
            throws MojoExecutionException {

        if (Map.class.isAssignableFrom(type)) {                 // arbitrary keys by design (<vars>)
            for (Xpp3Dom child : node.getChildren()) {
                String moved = RELOCATED_VARS.get(child.getName());
                if (moved != null) {
                    throw new MojoExecutionException(relocatedVar(child.getName(), path, moved));
                }
            }
            return;
        }

        if (Collection.class.isAssignableFrom(type)) {
            if (element == null || !isOwnType(element)) return;  // list of scalars: names are free
            for (Xpp3Dom item : node.getChildren()) {
                // Maven maps a child to a class named after the element in this
                // package first, falling back to the collection's own type —
                // which is how <toc/> and <page> sit among <section> elements.
                Class<?> itemType = namedType(item.getName(), element);
                checkNode(item, itemType, null, path + "<" + item.getName() + ">");
            }
            return;
        }

        if (isOwnType(type)) {
            for (Xpp3Dom child : node.getChildren()) {
                Field field = findField(type, child.getName());
                if (field == null) {
                    throw new MojoExecutionException(unknownNested(
                            child.getName(), path, type));
                }
                checkNode(child, field.getType(), elementType(field),
                        path + "<" + child.getName() + ">");
            }
            return;
        }

        checkScalar(node, type, path);
    }

    /** Reject a value Plexus would convert silently into something the author didn't mean. */
    private static void checkScalar(Xpp3Dom node, Class<?> type, String path)
            throws MojoExecutionException {
        if (node.getChildCount() > 0) {
            throw new MojoExecutionException(path + " takes a value, but declares nested elements ("
                    + names(node) + "). Write it as " + path.substring(path.lastIndexOf('<'))
                    + "value</" + node.getName() + ">.");
        }
        String value = node.getValue();
        if (value == null || value.isBlank()) return;   // empty means "unset"; Plexus tolerates it
        String v = value.trim();
        if (v.startsWith("${")) return;                 // unresolved property; not ours to judge

        if (type == boolean.class || type == Boolean.class) {
            if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                throw new MojoExecutionException(path + " must be true or false, got '" + v
                        + "'. Maven converts anything else to false without complaining, so the "
                        + "setting would look declared and do nothing.");
            }
        } else if (type == int.class || type == Integer.class) {
            requireNumber(v, path, "a whole number", true);
        } else if (type == float.class || type == Float.class
                || type == double.class || type == Double.class) {
            requireNumber(v, path, "a number", false);
        }
    }

    private static void requireNumber(String v, String path, String what, boolean integral)
            throws MojoExecutionException {
        try {
            if (integral) {
                Integer.parseInt(v);
            } else {
                Double.parseDouble(v);
            }
        } catch (NumberFormatException e) {
            throw new MojoExecutionException(path + " must be " + what + ", got '" + v + "'.");
        }
    }

    // ---- messages ----

    private static String unknownTopLevel(String name, String where, Set<String> accepted,
                                          Set<String> anyGoal, PluginDescriptor plugin) {
        StringBuilder sb = new StringBuilder(
                where + ": <" + name + "> is not a Paperband plugin parameter.");
        String moved = RELOCATED_VARS.get(name);
        if (moved != null) {
            sb.append(" It is part of the book, not a build setting — and no longer a var:")
                    .append(" declare it as <book>").append(moved).append("</book>.");
            return sb.toString();
        }
        if (KNOWN_VARS.contains(name)) {
            sb.append(" It is a book var, not a build setting — declare it inside the book:")
                    .append(" <book><vars><").append(name).append(">…</").append(name)
                    .append("></vars></book>.");
            return sb.toString();
        }
        if (anyGoal.contains(name)) {
            List<String> owners = new ArrayList<>();
            for (MojoDescriptor m : plugin.getMojos()) {
                if (parametersOf(m).containsKey(name)) owners.add(m.getGoal());
            }
            java.util.Collections.sort(owners);
            sb.append(" It belongs to the ").append(String.join("/", owners))
                    .append(owners.size() > 1 ? " goals" : " goal")
                    .append(", which this block does not configure.");
            return sb.toString();
        }
        String near = closest(name, accepted);
        if (near != null) {
            sb.append(" Did you mean <").append(near).append(">?");
        } else {
            sb.append(" Valid here: ").append(sorted(accepted)).append('.');
        }
        return sb.toString();
    }

    private static String relocatedVar(String name, String path, String moved) {
        return path + "<" + name + "> is no longer a var: declare it as <book>" + moved
                + "</book>. A var block takes any key, so this one parses and is then ignored —"
                + " the site would render with the setting looking declared and doing nothing.";
    }

    private static String unknownNested(String name, String path, Class<?> type) {
        StringBuilder sb = new StringBuilder(path + "<" + name + "> is not a valid child of "
                + path + ".");
        Set<String> known = parameterNames(type);
        String near = closest(name, known);
        if (near != null) {
            sb.append(" Did you mean <").append(near).append(">?");
        } else {
            sb.append(" Valid children: ").append(sorted(known)).append('.');
        }
        return sb.toString();
    }

    private static String sorted(Set<String> names) {
        List<String> out = new ArrayList<>(names);
        out.removeIf(n -> n.equals("project") || n.equals("mojoExecution"));   // readonly plumbing
        java.util.Collections.sort(out);
        return String.join(", ", out);
    }

    private static String names(Xpp3Dom node) {
        List<String> out = new ArrayList<>();
        for (Xpp3Dom c : node.getChildren()) out.add("<" + c.getName() + ">");
        return String.join(", ", out);
    }

    /** Nearest known name within a small edit distance, or null when nothing is close. */
    private static String closest(String name, Set<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String c : candidates) {
            int d = distance(name.toLowerCase(Locale.ROOT), c.toLowerCase(Locale.ROOT));
            if (d < bestDistance) {
                bestDistance = d;
                best = c;
            }
        }
        // Scale tolerance with length so short names don't match everything.
        return bestDistance <= Math.max(2, name.length() / 3) ? best : null;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[b.length()];
    }

    // ---- reflection ----

    /** Is this one of Paperband's own configuration types, whose children we can check strictly? */
    private static boolean isOwnType(Class<?> type) {
        return type != null && type.getPackageName().equals(OWN_PACKAGE);
    }

    /**
     * The class an element name binds to inside a collection: one named after
     * the element in this package, else the collection's declared type. Mirrors
     * how Maven's configurator resolves {@code <toc/>} and {@code <page>} among
     * {@code <section>} elements.
     */
    private static Class<?> namedType(String element, Class<?> fallback) {
        String candidate = OWN_PACKAGE + "." + Character.toUpperCase(element.charAt(0))
                + element.substring(1);
        try {
            Class<?> found = Class.forName(candidate);
            return fallback.isAssignableFrom(found) ? found : fallback;
        } catch (ClassNotFoundException e) {
            return fallback;
        }
    }

    /** Configurable field named {@code name}. Nested types are plain beans — no annotations involved. */
    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isSynthetic() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getName().equals(name)) return f;
            }
        }
        return null;
    }

    /** Every element name a nested type accepts. */
    private static Set<String> parameterNames(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isSynthetic() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                names.add(f.getName());
            }
        }
        return names;
    }

    /** The element type of a collection field, or null when it isn't one. */
    private static Class<?> elementType(Field field) {
        if (!Collection.class.isAssignableFrom(field.getType())) return null;
        Type generic = field.getGenericType();
        if (generic instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1
                && pt.getActualTypeArguments()[0] instanceof Class<?> c) {
            return c;
        }
        return null;
    }
}
