package dev.noregressions.paperband;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DataLoaderTest {

    @TempDir
    Path dir;

    private Path write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    @Test
    void loadsDirectoryRecursively() throws IOException {
        write("site.yml", "name: Paperband\nyear: 2026\n");
        write("team/members.json", "[{\"name\": \"Ann\"}, {\"name\": \"Bob\"}]");
        write("team/lead.yaml", "Ann\n");
        write("README.md", "ignored");

        Map<String, Object> data = DataLoader.load(List.of(dir));

        assertEquals(List.of("site", "team"), List.copyOf(data.keySet()));
        assertEquals(Map.of("name", "Paperband", "year", 2026), data.get("site"));
        Map<?, ?> team = (Map<?, ?>) data.get("team");
        assertEquals("Ann", team.get("lead"));
        assertEquals(List.of(Map.of("name", "Ann"), Map.of("name", "Bob")), team.get("members"));
    }

    @Test
    void loadsSingleFilesByName() throws IOException {
        Path a = write("a.yml", "x: 1\n");
        Path b = write("other/b.json", "{\"y\": true}");
        assertEquals(Map.of("a", Map.of("x", 1), "b", Map.of("y", true)), DataLoader.load(List.of(a, b)));
    }

    @Test
    void duplicateNamesAreAnError() throws IOException {
        write("site.yml", "a: 1\n");
        write("site.json", "{\"a\": 2}");
        IOException e = assertThrows(IOException.class, () -> DataLoader.load(List.of(dir)));
        assertTrue(e.getMessage().contains("data name \"site\" is defined twice"), e.getMessage());
    }

    @Test
    void duplicatesAcrossRootsAreAnError() throws IOException {
        Path a = write("one/x.yml", "a: 1\n");
        Path b = write("two/x.yml", "a: 2\n");
        assertThrows(IOException.class, () -> DataLoader.load(List.of(a, b)));
    }

    @Test
    void invalidFileNamesThePath() throws IOException {
        Path bad = write("bad.yml", "a: [unclosed\n");
        IOException e = assertThrows(IOException.class, () -> DataLoader.load(List.of(bad)));
        assertTrue(e.getMessage().startsWith("invalid data file " + bad), e.getMessage());
    }

    @Test
    void rejectsMissingOrUnsupportedPaths() throws IOException {
        Path txt = write("notes.txt", "x");
        assertThrows(IOException.class, () -> DataLoader.load(List.of(dir.resolve("missing"))));
        IOException e = assertThrows(IOException.class, () -> DataLoader.load(List.of(txt)));
        assertTrue(e.getMessage().contains(".yml, .yaml or .json"), e.getMessage());
    }
}
