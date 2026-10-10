package dev.noregressions.paperband.layout;

import dev.noregressions.paperband.AutoIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reads a manifest's {@code index:} file: the terms to find in the text, and where to look
 * ({@link AutoIndex}). Paths are relative to the manifest's directory.
 *
 * <pre>
 * files: [docs/*.md]                   # what's searched; default: every source
 * skip: [Cross-references, References] # headings whose sections aren't searched
 * mentions: 2                          # mentions a file needs to be indexed for a term; default 1
 * terms:
 *   - JAXB                             # a term on its own: found as whole words, as written
 *   - term: "`jdeps`"                  # shown as code; found as plain jdeps
 *     home: [docs/01.01-*.md]          # always indexed here
 *   - term: Security Manager
 *     match: 'Security ?Manager'       # a regular expression, for the ways the text says it
 *   - term: garbage collectors!G1      # a subentry: found by its own text, G1
 * </pre>
 */
public final class IndexSpec {

    private IndexSpec() {
    }

    /** The manifest's index, or null if it doesn't name one. */
    public static AutoIndex load(Manifest manifest) throws IOException {
        if (manifest.index() == null) {
            return null;
        }
        Path file = manifest.dir().resolve(manifest.index());
        if (!Files.isRegularFile(file)) {
            throw new ConfigException(manifest.name() + ": index: file not found: " + manifest.index());
        }
        return parse(Manifest.readYaml(file), manifest.index().toString());
    }

    /** An index from settings in the file's structure; {@code name} is used in messages. */
    public static AutoIndex parse(Object settings, String name) throws ConfigException {
        YamlSpec spec = YamlSpec.root(settings, name);
        List<String> files = spec.strings("files");
        List<String> skip = spec.strings("skip");
        String mentionsText = spec.string("mentions", "1");
        int mentions;
        try {
            mentions = Integer.parseInt(mentionsText);
        } catch (NumberFormatException e) {
            mentions = 0;
        }
        if (mentions < 1) {
            throw spec.error("mentions", "expected a whole number from 1, got \"" + mentionsText + "\"");
        }
        Object raw = spec.raw("terms");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new ConfigException(spec.child("terms") + ": expected a list of terms");
        }
        List<AutoIndex.Term> terms = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            terms.add(term(list.get(i), spec.child("terms") + "[" + i + "]"));
        }
        spec.finish();
        return new AutoIndex(terms, files.isEmpty() ? p -> true : any(files), skip, mentions);
    }

    private static AutoIndex.Term term(Object value, String context) throws ConfigException {
        if (value instanceof String s) {
            return new AutoIndex.Term(checked(s, context), AutoIndex.defaultMatch(s), p -> false);
        }
        YamlSpec t = YamlSpec.of(value, context);
        String term = checked(t.string("term", null), t.child("term"));
        String match = t.string("match", null);
        List<String> home = t.strings("home");
        t.finish();
        Pattern pattern;
        try {
            pattern = match == null ? AutoIndex.defaultMatch(term) : Pattern.compile(match);
        } catch (PatternSyntaxException e) {
            throw new ConfigException(t.child("match") + ": not a valid regular expression: "
                    + e.getDescription() + " in \"" + match + "\"");
        }
        return new AutoIndex.Term(term, pattern, home.isEmpty() ? p -> false : any(home));
    }

    private static String checked(String term, String context) throws ConfigException {
        if (term == null || term.isBlank()) {
            throw new ConfigException(context + ": expected a term");
        }
        String[] parts = term.split("!", -1);
        if (term.contains(";") || parts.length > 2 || java.util.Arrays.stream(parts).anyMatch(String::isBlank)) {
            throw new ConfigException(context + ": \"" + term + "\" isn't a term: no ;, and at most one ! (term!subentry)");
        }
        return term.strip();
    }

    private static Predicate<Path> any(List<String> globs) {
        List<Predicate<Path>> matchers = globs.stream().map(Globs::matcher).toList();
        return p -> matchers.stream().anyMatch(m -> m.test(p));
    }
}
