package dev.noregressions.paperband;

import java.io.IOException;
import java.nio.file.Path;

/** Transforms a markdown file's raw source before it is parsed into the AST. */
@FunctionalInterface
public interface Preprocessor {

    Preprocessor NONE = (path, source) -> source;

    String process(Path path, String source) throws IOException;
}
