package dev.noregressions.paperband.plantuml;

import dev.noregressions.paperband.blocks.BlockRenderer;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.core.DiagramDescription;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * {@code ```plantuml} blocks as inline SVG. The block is the diagram's source, with or without
 * its {@code @startuml}/{@code @enduml} lines. A diagram PlantUML can't draw stops the build
 * with PlantUML's message, rather than putting PlantUML's error picture in the book.
 */
public final class PlantUmlRenderer implements BlockRenderer {

    @Override
    public Set<String> languages() {
        return Set.of("plantuml");
    }

    @Override
    public String render(String language, String source) throws IOException {
        String text = source.strip().startsWith("@start") ? source : "@startuml\n" + source + "\n@enduml\n";
        SourceStringReader reader = new SourceStringReader(text);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DiagramDescription d = reader.outputImage(out, new FileFormatOption(FileFormat.SVG));
        String svg = out.toString(StandardCharsets.UTF_8);
        if (d == null || d.getDescription() == null || d.getDescription().toLowerCase().contains("error")) {
            throw new IOException("PlantUML couldn't draw the diagram"
                    + (d == null || d.getDescription() == null ? "" : ": " + d.getDescription()));
        }
        int start = svg.indexOf("<svg");
        return start < 0 ? svg : svg.substring(start); // no XML prolog inside HTML
    }
}
