package app.dilmun.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * An agent proposes; it never commits. The arbiters hand it a directive and
 * the text of the one source the directive names, and get back a proposal:
 * {tools: [...], facts: [{ident: ["name", E], a, v, nu, quote: {start, end, text}}]}.
 *
 * The on-device model (MiniCPM5-1B) will implement this interface in a later
 * build. This build ships {@link PatternAgent} in the model's slot, so the
 * whole path from directive to culture can be exercised today.
 */
public interface Agent {
    /** Recorded as model_hash on every result, so you can tell which agent said what. */
    String id();

    Map<String, Object> propose(Map<String, Object> directive, String text);

    /**
     * Reads lines written as "entity | attribute | value" (a leading "- " or
     * "* " is fine) and proposes each as a fact, quoting the whole line.
     */
    final class PatternAgent implements Agent {
        @Override public String id() { return "pattern-agent:1"; }

        @Override public Map<String, Object> propose(Map<String, Object> directive, String text) {
            List<Object> facts = new ArrayList<>();
            int pos = 0;
            while (pos <= text.length()) {
                int nl = text.indexOf('\n', pos);
                int end = nl < 0 ? text.length() : nl;
                String raw = text.substring(pos, end);
                if (raw.endsWith("\r")) { raw = raw.substring(0, raw.length() - 1); end--; }
                String line = raw.trim();
                if (line.startsWith("- ") || line.startsWith("* ")) line = line.substring(2).trim();
                String[] parts = line.split("\\|", -1);
                if (parts.length == 3) {
                    String e = parts[0].trim(), a = parts[1].trim().toLowerCase().replace(' ', '_'), v = parts[2].trim();
                    if (!e.isEmpty() && !a.isEmpty() && !v.isEmpty()) {
                        facts.add(Tx.m(
                                "ident", Arrays.asList("name", e),
                                "a", a, "v", v, "nu", Policy.PATTERN_NU,
                                "quote", Tx.m("start", (long) pos, "end", (long) end, "text", raw)));
                    }
                }
                if (nl < 0) break;
                pos = nl + 1;
            }
            return Tx.m("tools", new ArrayList<Object>(Arrays.asList("read_source")), "facts", facts);
        }
    }
}
