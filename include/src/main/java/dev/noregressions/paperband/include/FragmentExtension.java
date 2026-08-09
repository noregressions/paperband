package dev.noregressions.paperband.include;

import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.tokenParser.TokenParser;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Registers the {@code {% fragment %}} tag with a Pebble engine. Built fresh
 * per source file by {@link PebbleIncludePreprocessor} so the tag can close
 * over that file's path without threading it through the evaluation context.
 */
final class FragmentExtension extends AbstractExtension {

    private final Map<String, ContentProvider> providers;
    private final Map<String, FragmentProcessor> processors;
    private final Path sourceFile;
    private final Path bookRoot;
    private final Map<String, Map<String, Object>> providerConfigs;

    FragmentExtension(
            Map<String, ContentProvider> providers,
            Map<String, FragmentProcessor> processors,
            Path sourceFile,
            Path bookRoot,
            Map<String, Map<String, Object>> providerConfigs) {
        this.providers = providers;
        this.processors = processors;
        this.sourceFile = sourceFile;
        this.bookRoot = bookRoot;
        this.providerConfigs = providerConfigs;
    }

    @Override
    public List<TokenParser> getTokenParsers() {
        return List.of(new FragmentTokenParser(providers, processors, sourceFile, bookRoot, providerConfigs));
    }
}
