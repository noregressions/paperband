package dev.noregressions.paperband.layout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transforms written as statements, one to a line, for a view's
 * {@code transform.html} that lists them rather than calling {@code result}:
 *
 * <pre>
 * drop .instructor-note
 * blank .solution as answer-space
 * insert .answer lines=6 after .exercise:not(:has(.solution))
 * </pre>
 *
 * <p>Each statement is one transform, the filter of the same meaning, so it
 * does exactly what {@code card | ...} does in a template: the statements are
 * another way to write the pipe, not another engine. They run in order, each
 * finding its matches before it changes anything, and each sees what the ones
 * before it did.
 *
 * <table>
 *   <caption>The statements and the transforms they run</caption>
 *   <tr><td>{@code drop SEL}, {@code keep SEL}</td><td>{@code drop}, {@code keep}</td></tr>
 *   <tr><td>{@code blank SEL as NAME}</td><td>{@code blank}</td></tr>
 *   <tr><td>{@code replace SEL with BLOCK}</td><td>{@code replace}</td></tr>
 *   <tr><td>{@code insert BLOCK before SEL}, {@code after SEL}</td><td>{@code insertBefore}, {@code insertAfter}</td></tr>
 *   <tr><td>{@code insert BLOCK first in SEL}, {@code last in SEL}</td><td>{@code prepend}, {@code append}</td></tr>
 *   <tr><td>{@code wrap SEL in BLOCK}</td><td>{@code wrap}</td></tr>
 *   <tr><td>{@code add .NAME to SEL}, {@code remove .NAME from SEL}</td><td>{@code addClass}, {@code removeClass}</td></tr>
 *   <tr><td>{@code set ATTRS on SEL}</td><td>{@code set}</td></tr>
 * </table>
 *
 * <p>A keyword is a word on its own, outside brackets and quotes, so a
 * selector can hold any of them in an attribute value. A blank line, or one
 * starting with {@code #}, is skipped.
 */
final class TransformStatements {

    private TransformStatements() {
    }

    /**
     * One statement as written, and the transform and arguments it runs.
     *
     * @param text      the statement as written, for a failure to quote
     * @param transform the transform's name, as its filter is named
     * @param args      the filter's arguments by name
     */
    record Statement(String text, String transform, Map<String, Object> args) {
    }

    /** Every statement form, for a failure to list. */
    static final String FORMS = "drop SEL, keep SEL, blank SEL as NAME, replace SEL with BLOCK,"
            + " insert BLOCK before|after SEL, insert BLOCK first|last in SEL, wrap SEL in BLOCK,"
            + " add .NAME to SEL, remove .NAME from SEL, set ATTRS on SEL";

    /**
     * The statements in {@code text}, in order.
     *
     * @throws IllegalArgumentException naming the first statement that isn't one, and why
     */
    static List<Statement> parse(String text) {
        List<Statement> out = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            out.add(statement(line));
        }
        return out;
    }

    private static Statement statement(String line) {
        List<String> words = words(line);
        String verb = words.get(0);
        List<String> rest = words.subList(1, words.size());
        Map<String, Object> args = new LinkedHashMap<>();
        String transform;
        switch (verb) {
            case "drop", "keep" -> {
                transform = verb;
                args.put("selector", part(line, rest, "a selector", verb + " SEL"));
            }
            case "blank" -> {
                transform = "blank";
                int as = keyword(line, rest, "as", "blank SEL as NAME");
                args.put("selector", part(line, rest.subList(0, as), "a selector", "blank SEL as NAME"));
                args.put("name", className(line, rest.subList(as + 1, rest.size()), "blank SEL as NAME"));
            }
            case "replace" -> {
                transform = "replace";
                int with = keyword(line, rest, "with", "replace SEL with BLOCK");
                args.put("selector", part(line, rest.subList(0, with), "a selector", "replace SEL with BLOCK"));
                args.put("block", part(line, rest.subList(with + 1, rest.size()), "a block", "replace SEL with BLOCK"));
            }
            case "insert" -> {
                String form = "insert BLOCK before|after SEL, or insert BLOCK first|last in SEL";
                int at = -1;
                int skip = 1;
                transform = null;
                for (int i = 0; i < rest.size() && at < 0; i++) {
                    String w = rest.get(i);
                    boolean in = i + 1 < rest.size() && rest.get(i + 1).equals("in");
                    if (w.equals("before")) transform = "insertBefore";
                    else if (w.equals("after")) transform = "insertAfter";
                    else if (w.equals("first") && in) transform = "prepend";
                    else if (w.equals("last") && in) transform = "append";
                    else continue;
                    at = i;
                    if (transform.equals("prepend") || transform.equals("append")) skip = 2;
                }
                if (at < 0) throw wrong(line, "says where: " + form);
                args.put("block", part(line, rest.subList(0, at), "a block", form));
                args.put("selector", part(line, rest.subList(at + skip, rest.size()), "a selector", form));
            }
            case "wrap" -> {
                transform = "wrap";
                int in = keyword(line, rest, "in", "wrap SEL in BLOCK");
                args.put("selector", part(line, rest.subList(0, in), "a selector", "wrap SEL in BLOCK"));
                args.put("block", part(line, rest.subList(in + 1, rest.size()), "a block", "wrap SEL in BLOCK"));
            }
            case "add", "remove" -> {
                transform = verb.equals("add") ? "addClass" : "removeClass";
                String joint = verb.equals("add") ? "to" : "from";
                String form = verb + " .NAME " + joint + " SEL";
                int at = keyword(line, rest, joint, form);
                args.put("name", className(line, rest.subList(0, at), form));
                args.put("selector", part(line, rest.subList(at + 1, rest.size()), "a selector", form));
            }
            case "set" -> {
                transform = "set";
                int on = keyword(line, rest, "on", "set ATTRS on SEL");
                args.put("attributes", part(line, rest.subList(0, on), "attributes", "set ATTRS on SEL"));
                args.put("selector", part(line, rest.subList(on + 1, rest.size()), "a selector", "set ATTRS on SEL"));
            }
            default -> throw wrong(line, "doesn't start with a statement's word. The statements are: " + FORMS);
        }
        return new Statement(line, transform, args);
    }

    /**
     * {@code line} split into words at whitespace outside brackets and quotes,
     * so {@code [title="a with b"]} is one word and its {@code with} isn't a keyword.
     */
    private static List<String> words(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[' || c == '(' || c == '{') {
                depth++;
            } else if ((c == ']' || c == ')' || c == '}') && depth > 0) {
                depth--;
            } else if (Character.isWhitespace(c) && depth == 0) {
                if (!word.isEmpty()) out.add(word.toString());
                word.setLength(0);
                continue;
            }
            word.append(c);
        }
        if (quote != 0 || depth != 0) throw wrong(line, "opens a quote or bracket it doesn't close");
        if (!word.isEmpty()) out.add(word.toString());
        return out;
    }

    /** Where {@code keyword} first stands in {@code words}. */
    private static int keyword(String line, List<String> words, String keyword, String form) {
        int at = words.indexOf(keyword);
        if (at < 0) throw wrong(line, "has no '" + keyword + "': it's " + form);
        return at;
    }

    /** {@code words} joined again, or a failure when there are none. */
    private static String part(String line, List<String> words, String what, String form) {
        if (words.isEmpty()) throw wrong(line, "is missing " + what + ": it's " + form);
        return String.join(" ", words);
    }

    /** One class name, with or without its dot. */
    private static String className(String line, List<String> words, String form) {
        if (words.size() != 1) throw wrong(line, "needs one class name: it's " + form);
        String name = words.get(0);
        return name.startsWith(".") ? name.substring(1) : name;
    }

    private static IllegalArgumentException wrong(String line, String why) {
        return new IllegalArgumentException("'" + line + "' " + why);
    }
}
