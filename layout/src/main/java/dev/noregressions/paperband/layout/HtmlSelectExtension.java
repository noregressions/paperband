package dev.noregressions.paperband.layout;

import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.extension.Filter;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.jsoup.select.Selector;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * {@code | select('css')}: the parts of some HTML that match a CSS selector,
 * for layout templates that build one document out of pieces of another.
 *
 * <pre>
 * {{ block.html | select('p.instructions, pre.console') | raw }}
 * </pre>
 *
 * <p>A block's content reaches a template as one HTML string, and Pebble has
 * no way to look inside it. Slots move whole blocks; this picks parts out of
 * one -- the paragraph a card marked {@code {.instructions}}, the console
 * session under it -- by the classes the tree phase already put on them. It
 * returns their outer HTML in document order, joined by newlines, and an
 * element inside another match isn't repeated. No match is an empty string, so
 * {@code {% if block.html | select('...') %}} works as a test.
 *
 * <p>Like {@code block.html} itself the result is HTML, so it's printed with
 * {@code | raw}. It's the card's own already-sanitised markup, cut rather than
 * rewritten.
 */
final class HtmlSelectExtension extends AbstractExtension {

    @Override
    public Map<String, Filter> getFilters() {
        return Map.of("select", new SelectFilter());
    }

    private static final class SelectFilter implements Filter {

        @Override
        public List<String> getArgumentNames() {
            return List.of("selector");
        }

        @Override
        public Object apply(Object input, Map<String, Object> args, PebbleTemplate self,
                            EvaluationContext context, int lineNumber) throws PebbleException {
            Object selector = args.get("selector");
            if (selector == null || selector.toString().isBlank()) {
                throw new PebbleException(null, "select needs a CSS selector, as in"
                        + " select('p.instructions')", lineNumber, self.getName());
            }
            if (input == null) return "";
            Elements matches;
            try {
                matches = Jsoup.parseBodyFragment(input.toString()).body().select(selector.toString());
            } catch (Selector.SelectorParseException e) {
                throw new PebbleException(e, "select('" + selector + "') isn't a CSS selector jsoup"
                        + " can read: " + e.getMessage(), lineNumber, self.getName());
            }
            StringJoiner out = new StringJoiner("\n");
            for (Element el : matches) {
                if (insideAnother(el, matches)) continue;
                out.add(el.outerHtml());
            }
            return out.toString();
        }

        /** Whether an ancestor of {@code el} is itself a match, so its HTML already includes {@code el}. */
        private static boolean insideAnother(Element el, Elements matches) {
            for (Element parent : el.parents()) {
                if (matches.contains(parent)) return true;
            }
            return false;
        }
    }
}
