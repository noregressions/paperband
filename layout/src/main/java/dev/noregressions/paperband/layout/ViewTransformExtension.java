package dev.noregressions.paperband.layout;

import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.extension.Function;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;

import java.util.List;
import java.util.Map;

/**
 * {@code result(card)}: how a view's {@code transform.html} hands back the
 * card it changed.
 *
 * <pre>
 * {# layouts/student/transform.html #}
 * {{ result(card | replace('.solution', '{.answer-space}')) }}
 * </pre>
 *
 * <p>A template writes text, and a transform has to give the engine a card,
 * not HTML. So the engine puts a {@link Result} in the transform's model, and
 * {@code result} stores its argument there and prints nothing. Anywhere else
 * there's no {@link Result} to store it in, and it fails.
 */
final class ViewTransformExtension extends AbstractExtension {

    /** The model key a {@link Result} sits under. Not a name a template would choose. */
    static final String KEY = "paperbandTransformResult";

    /** What a transform handed back, and whether it handed back anything. */
    static final class Result {
        private Object value;
        private boolean set;

        Object value() {
            return value;
        }

        boolean set() {
            return set;
        }
    }

    @Override
    public Map<String, Function> getFunctions() {
        return Map.of("result", new ResultFunction());
    }

    private static final class ResultFunction implements Function {

        @Override
        public List<String> getArgumentNames() {
            return List.of("card");
        }

        @Override
        public Object execute(Map<String, Object> args, PebbleTemplate self, EvaluationContext context,
                              int lineNumber) {
            if (!(context.getVariable(KEY) instanceof Result r)) {
                throw new PebbleException(null, "result(...) hands a view's transform.html its changed card,"
                        + " and works only there", lineNumber, self.getName());
            }
            if (r.set) {
                throw new PebbleException(null, "transform.html called result(...) twice; hand back one card",
                        lineNumber, self.getName());
            }
            r.value = args.get("card");
            r.set = true;
            return "";
        }
    }
}
