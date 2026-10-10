package dev.noregressions.paperband.config;

import dev.noregressions.paperband.layout.Manifest;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one build needs, however it was specified. The command line and the Maven plugin
 * both make one with {@link Builder} and hand it to {@link Runner}.
 * <p>
 * The settings are the manifest's (see {@link Manifest}): a builder can start from a manifest
 * file and then override any of them, or specify everything itself. Either way the result goes
 * through the same checks as a manifest file, so behaviour and error messages are the same
 * wherever the settings came from.
 *
 * @param manifest    what to build
 * @param clock       the source of {@code build.date}
 * @param astOutput   where to write the AST debug page, or null
 * @param draftOutput where to write the draft AST debug page, or null
 */
public record BuildConfig(Manifest manifest, Clock clock, Path astOutput, Path draftOutput) {

    /** @param baseDir what relative paths given to the builder are relative to (e.g. the working directory) */
    public static Builder builder(Path baseDir) {
        return new Builder(baseDir.toAbsolutePath());
    }

    /**
     * Collects settings. Values set here override the manifest file's: single values and lists
     * replace them, {@code vars} and {@code defaults} are merged key by key, and {@code files}
     * replaces {@code sources}/{@code sequence}/{@code hidden} (and the other way round).
     * Paths are relative to the builder's base directory.
     */
    public static final class Builder {

        private final Path baseDir;
        private final Map<String, Object> settings = new LinkedHashMap<>();
        private Path manifestFile;
        private String origin = "settings";
        private Clock clock = Clock.systemDefaultZone();
        private Path astOutput;
        private Path draftOutput;

        private Builder(Path baseDir) {
            this.baseDir = baseDir;
        }

        /** Start from a manifest file's settings. */
        public Builder manifest(Path file) {
            this.manifestFile = file == null ? null : baseDir.resolve(file);
            return this;
        }

        /** How to refer to these settings in messages when there's no manifest file, e.g. {@code pom.xml}. */
        public Builder origin(String name) {
            this.origin = name;
            return this;
        }

        /**
         * Sets any manifest key to a value in the manifest's structure (strings, numbers, lists,
         * maps); {@link Path}s anywhere in it are relative to the base directory. Null removes the key.
         */
        public Builder set(String key, Object value) {
            if (value == null) {
                settings.remove(key);
            } else {
                settings.put(key, value);
            }
            return this;
        }

        public Builder title(String title) {
            return set("title", title);
        }

        /** For pdf: an installed browser's channel (e.g. {@code chrome}) or executable path. */
        public Builder browser(String browser) {
            return set("browser", browser);
        }

        /** Where to write the site. */
        public Builder output(Path output) {
            return set("output", output);
        }

        public Builder stylesheets(List<Path> stylesheets) {
            return set("stylesheets", stylesheets);
        }

        public Builder templates(Path dir) {
            return set("templates", dir);
        }

        public Builder layouts(Path dir) {
            return set("layouts", dir);
        }

        public Builder data(List<Path> roots) {
            return set("data", roots);
        }

        public Builder vars(Map<String, String> vars) {
            return set("vars", vars);
        }

        /** Names of environment variables to expose as {@code env}. */
        public Builder env(List<String> names) {
            return set("env", names);
        }

        /** Source patterns; use with {@link #sequence}. */
        public Builder sources(List<String> patterns) {
            return set("sources", patterns);
        }

        /** Exact files that are both the sources and the sequence, in order. */
        public Builder files(List<Path> files) {
            return set("files", files);
        }

        /**
         * Sequence entries, each a map like a manifest's: {@code file}, {@code files}, {@code where},
         * {@code sort}, {@code layout}.
         */
        public Builder sequence(List<Map<String, Object>> entries) {
            return set("sequence", entries);
        }

        public Builder hidden(List<String> patterns) {
            return set("hidden", patterns);
        }

        /** warn, error or ignore. */
        public Builder unused(String policy) {
            return set("unused", policy);
        }

        public Builder defaultLayout(String layout) {
            return defaults("layout", layout);
        }

        /** A page spec in the manifest's structure, e.g. a map of size, orientation, margin. */
        public Builder defaultPage(Object page) {
            return defaults("page", page);
        }

        private Builder defaults(String key, Object value) {
            @SuppressWarnings("unchecked")
            Map<String, Object> defaults = (Map<String, Object>) settings.computeIfAbsent("defaults", k -> new LinkedHashMap<>());
            defaults.put(key, value);
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Builder astOutput(Path file) {
            this.astOutput = file == null ? null : baseDir.resolve(file);
            return this;
        }

        public Builder draftOutput(Path file) {
            this.draftOutput = file == null ? null : baseDir.resolve(file);
            return this;
        }

        /** Checks everything and makes the configuration. */
        public BuildConfig build() throws IOException {
            Path dir = manifestFile != null ? manifestFile.getParent() : baseDir;
            Map<String, Object> merged = new LinkedHashMap<>();
            if (manifestFile != null) {
                Object yaml = Manifest.readYaml(manifestFile);
                if (yaml instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> merged.put(String.valueOf(k), v));
                } else if (yaml != null) {
                    // Let the manifest's own checks report what's wrong with it.
                    Manifest.fromSettings(yaml, dir, manifestFile.getFileName().toString(), manifestFile);
                }
            }
            if (settings.containsKey("files")) {
                merged.remove("sources");
                merged.remove("sequence");
                merged.remove("hidden");
            }
            if (settings.containsKey("sources") || settings.containsKey("sequence")) {
                merged.remove("files");
            }
            for (var e : settings.entrySet()) {
                Object value = plain(e.getValue(), dir);
                Object base = merged.get(e.getKey());
                if (value instanceof Map<?, ?> over && base instanceof Map<?, ?> under) {
                    Map<String, Object> both = new LinkedHashMap<>();
                    under.forEach((k, v) -> both.put(String.valueOf(k), v));
                    over.forEach((k, v) -> both.put(String.valueOf(k), v));
                    value = both;
                }
                merged.put(e.getKey(), value);
            }
            String name = manifestFile != null ? manifestFile.getFileName().toString() : origin;
            return new BuildConfig(Manifest.fromSettings(merged, dir, name, manifestFile), clock, astOutput, draftOutput);
        }

        /**
         * Settings in the manifest's plain structure. A path relative to the base directory is
         * kept relative when the manifest resolves against the same directory, and made absolute
         * otherwise, so it means the same thing either way.
         */
        private Object plain(Object value, Path dir) {
            return switch (value) {
                case Path p -> {
                    Path resolved = baseDir.resolve(p).normalize();
                    yield (dir.equals(baseDir) && !p.isAbsolute() ? p.normalize() : resolved).toString();
                }
                case Map<?, ?> m -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    m.forEach((k, v) -> out.put(String.valueOf(k), plain(v, dir)));
                    yield out;
                }
                case List<?> l -> {
                    List<Object> out = new ArrayList<>();
                    l.forEach(v -> out.add(plain(v, dir)));
                    yield out;
                }
                default -> value;
            };
        }
    }
}
