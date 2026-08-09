package dev.noregressions.paperband.cards;

import java.nio.file.Path;

/**
 * Hook for transforming raw markdown text before {@link CardLoader} hands it
 * to flexmark. The classic use is content-include directive substitution
 * ({@code include}); other pre-flexmark passes (variable
 * interpolation, conditional sections) could plug in here later.
 *
 * <p>Implementations are expected to be deterministic and side-effect-free
 * w.r.t. their inputs: same markdown + same source file path produces the
 * same output.
 */
@FunctionalInterface
public interface MarkdownPreprocessor {

    /**
     * Transform {@code markdown} into a new markdown string. The source file
     * path is supplied so that path-resolving preprocessors (file includes,
     * relative-link rewrites) can resolve references against the markdown's
     * own location.
     *
     * @param markdown   raw markdown text (including any frontmatter)
     * @param sourceFile absolute path of the markdown file; never null
     * @return transformed markdown
     */
    String process(String markdown, Path sourceFile);
}
