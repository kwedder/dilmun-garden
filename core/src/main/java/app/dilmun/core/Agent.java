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
 * With no model loaded, {@link RulesAgent} fills the slot: the arbiters' own
 * rules, so prose still gives facts. {@link ModelAgent} adds a model's reading
 * on top of the same rules.
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

    /**
     * The arbiters' rules alone, with no model: the lines PatternAgent reads,
     * plus what the grounding rules harvest from each readable sentence
     * ("E is a V", "E, a V", "V such as A, B"). The same rules ModelAgent runs
     * before it asks its model, so a phone without a model still collects facts.
     */
    final class RulesAgent implements Agent {
        @Override public String id() { return "rules-agent:1"; }

        @Override public Map<String, Object> propose(Map<String, Object> directive, String text) {
            Map<String, Object> p = new PatternAgent().propose(directive, text);
            @SuppressWarnings("unchecked") List<Object> facts = (List<Object>) p.get("facts");
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Object o : facts) {
                @SuppressWarnings("unchecked") Map<String, Object> f = (Map<String, Object>) o;
                seen.add(((List<?>) f.get("ident")).get(1) + "|" + f.get("a") + "|" + f.get("v"));
            }
            for (int[] x : ModelAgent.readable(text, 0, text.length())) {
                String s = text.substring(x[0], x[1]);
                if (s.trim().startsWith("- ") && s.split("\\|", -1).length == 3) continue;   // a written fact line: PatternAgent has it
                for (String[] h : Grounding.harvest(s))
                    if (seen.add(h[0] + "|" + h[1] + "|" + h[2]))
                        facts.add(Tx.m("ident", Arrays.asList("name", h[0]), "a", h[1], "v", h[2], "nu", Policy.PATTERN_NU, "by", "rules",
                                "quote", Tx.m("start", (long) x[0], "end", (long) x[1], "text", s)));
            }
            return p;
        }
    }
}
