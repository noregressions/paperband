package dev.noregressions.paperband.config;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Computes the set of "built-in" variables auto-injected into the
 * {@code vars} map of every {@link dev.noregressions.paperband.model.RenderContext}.
 *
 * <p>Built-ins are seeded <em>before</em> the {@code paperband.yaml} cascade,
 * so any user-supplied entry with the same key wins. That makes the dates
 * pinnable for reproducible builds — set {@code build_date: "2026-01-15"}
 * in the book root yaml and that value flows through instead of "now".
 *
 * <p>Built-ins are accessible from both markdown body (via the whole-body
 * Pebble pass — {@code {{ vars.X }}}, {@code {% if vars.X %}}, etc. — see
 * {@code PebbleIncludePreprocessor} in {@code include}) and from
 * final-render Pebble templates ({@code {{ vars.X }}} via the layout engine
 * model). Same map, two consumers.
 *
 * <h2>Available keys</h2>
 * <table>
 *   <caption>List of built-in variables and their formats.</caption>
 *   <tr><th>Key</th>              <th>Example</th>                  <th>Format</th></tr>
 *   <tr><td>{@code build_date}</td>      <td>{@code 2026-05-12}</td>        <td>ISO local date</td></tr>
 *   <tr><td>{@code build_date_long}</td> <td>{@code May 12, 2026}</td>      <td>Long form, US-style</td></tr>
 *   <tr><td>{@code build_year}</td>      <td>{@code 2026}</td>              <td>4-digit year</td></tr>
 *   <tr><td>{@code build_month_year}</td><td>{@code May 2026}</td>          <td>Month + year</td></tr>
 *   <tr><td>{@code build_iso}</td>       <td>{@code 2026-05-12T14:23:01Z}</td><td>ISO 8601 instant, UTC</td></tr>
 * </table>
 *
 * <p>All keys use the {@code build_} prefix to namespace them clearly against
 * user-defined vars. Locale is fixed to {@code Locale.ENGLISH} so month
 * names don't drift with the JVM's default locale.
 */
public final class BuiltInVars {

    private static final DateTimeFormatter DATE_LONG =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_YEAR =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private BuiltInVars() {}

    /**
     * Compute built-ins using the system UTC clock.
     *
     * @return map of built-in variables
     */
    public static Map<String, Object> compute() {
        return compute(Clock.systemUTC());
    }

    /**
     * Compute built-ins against the supplied clock. Exposed for tests so they
     * don't depend on real time. Production callers should use {@link #compute()}.
     */
    static Map<String, Object> compute(Clock clock) {
        LocalDate today = LocalDate.now(clock);
        Instant now = Instant.now(clock);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("build_date",       today.format(DateTimeFormatter.ISO_LOCAL_DATE));
        out.put("build_date_long",  today.format(DATE_LONG));
        out.put("build_year",       String.valueOf(today.getYear()));
        out.put("build_month_year", today.format(MONTH_YEAR));
        out.put("build_iso",        DateTimeFormatter.ISO_INSTANT.format(now));
        return out;
    }
}
