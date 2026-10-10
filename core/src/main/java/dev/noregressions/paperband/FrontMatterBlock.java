package dev.noregressions.paperband;

import org.commonmark.node.CustomBlock;
import org.commonmark.node.CustomNode;

import java.util.Map;

/**
 * A file's front matter in the AST: the first child of its {@link MarkdownFile}, with one
 * {@link Entry} child per top-level key.
 */
public class FrontMatterBlock extends CustomBlock {

    private final Map<String, Object> data;

    public FrontMatterBlock(Map<String, Object> data) {
        this.data = data;
        data.forEach((k, v) -> appendChild(new Entry(k, v)));
    }

    public Map<String, Object> getData() {
        return data;
    }

    public static class Entry extends CustomNode {

        private final String key;
        private final Object value;

        public Entry(String key, Object value) {
            this.key = key;
            this.value = value;
        }

        public String getKey() {
            return key;
        }

        /** A string, number, boolean, date, list or map. */
        public Object getValue() {
            return value;
        }
    }
}
