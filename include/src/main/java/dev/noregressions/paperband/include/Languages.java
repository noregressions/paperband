package dev.noregressions.paperband.include;

import java.util.Map;
import java.util.Optional;

/**
 * Map a file extension to a language hint usable as a fenced code-block
 * language tag. Best-effort: unknown extensions return {@link Optional#empty()}.
 *
 * <p>The returned identifier is what flexmark / highlight.js / Prism will see
 * as the info string on a fenced block. If you find yourself adding an
 * extension here, consider whether the fence renderer downstream actually
 * recognises the language.
 */
final class Languages {

    private Languages() {}

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("java", "java"),
            Map.entry("kt", "kotlin"),
            Map.entry("groovy", "groovy"),
            Map.entry("scala", "scala"),
            Map.entry("py", "python"),
            Map.entry("rb", "ruby"),
            Map.entry("go", "go"),
            Map.entry("rs", "rust"),
            Map.entry("c", "c"),
            Map.entry("h", "c"),
            Map.entry("cpp", "cpp"),
            Map.entry("hpp", "cpp"),
            Map.entry("cs", "csharp"),
            Map.entry("js", "javascript"),
            Map.entry("mjs", "javascript"),
            Map.entry("ts", "typescript"),
            Map.entry("tsx", "tsx"),
            Map.entry("jsx", "jsx"),
            Map.entry("html", "html"),
            Map.entry("htm", "html"),
            Map.entry("xml", "xml"),
            Map.entry("css", "css"),
            Map.entry("scss", "scss"),
            Map.entry("sass", "sass"),
            Map.entry("json", "json"),
            Map.entry("yml", "yaml"),
            Map.entry("yaml", "yaml"),
            Map.entry("toml", "toml"),
            Map.entry("ini", "ini"),
            Map.entry("properties", "properties"),
            Map.entry("sh", "bash"),
            Map.entry("bash", "bash"),
            Map.entry("zsh", "bash"),
            Map.entry("sql", "sql"),
            Map.entry("md", "markdown"),
            Map.entry("markdown", "markdown"),
            Map.entry("dockerfile", "dockerfile"),
            Map.entry("gradle", "groovy")
    );

    static Optional<String> languageFor(String extension) {
        if (extension == null || extension.isEmpty()) return Optional.empty();
        return Optional.ofNullable(BY_EXTENSION.get(extension.toLowerCase()));
    }
}
