package app.dilmun.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Arbiter policy and the genesis defaults every portal shares. Fixed in code. */
public final class Policy {
    private Policy() {}

    /** Facts a single result may carry. */
    public static final long DEFAULT_BUDGET = 50;
    /** A directive expires this long after it is issued. */
    public static final long TTL_MS = 10 * 60 * 1000L;
    /** Open directives one portal may hold. */
    public static final int OPEN_LIMIT = 8;
    /** Distinct sources the gate needs before it promotes a fact on its own. */
    public static final int GATE_K = 2;
    /** Confidence the pattern agent gives its facts, in thousandths. */
    public static final long PATTERN_NU = 800;
    /** Trace events the engine keeps in memory for the live map and the activity card. */
    public static final int TRACE_KEEP = 400;

    public static final Map<String, List<Long>> DEFAULT_SKILLS_VERSIONS =
            Collections.singletonMap("ingest", Collections.singletonList(1L));

    public static Map<String, TreeMap<Long, List<String>>> defaultSkills() {
        TreeMap<String, TreeMap<Long, List<String>>> out = new TreeMap<>();
        TreeMap<Long, List<String>> ingest = new TreeMap<>();
        ingest.put(1L, new ArrayList<>(Collections.singletonList("read_source")));
        out.put("ingest", ingest);
        return out;
    }

    /**
     * The starting schema. card: one or many. unique: identity or none.
     * kind: claim (settled by evidence) or state (settled by valid time).
     * ref: the value names another entity.
     */
    public static Map<String, Map<String, Object>> defaultSchema() {
        TreeMap<String, Map<String, Object>> s = new TreeMap<>();
        s.put("name",       attr("one",  "identity", "state", false));
        s.put("is_a",       attr("many", null,       "claim", true));
        s.put("part_of",    attr("many", null,       "claim", true));
        s.put("treats",     attr("many", null,       "claim", true));
        s.put("causes",     attr("many", null,       "claim", true));
        s.put("contains",   attr("many", null,       "claim", true));
        s.put("located_in", attr("many", null,       "claim", true));
        s.put("author",     attr("many", null,       "claim", false));
        s.put("defined_as", attr("one",  null,       "claim", false));
        s.put("date",       attr("one",  null,       "state", false));
        return s;
    }

    private static Map<String, Object> attr(String card, String unique, String kind, boolean ref) {
        return Tx.m("card", card, "unique", unique, "kind", kind, "ref", ref);
    }

    public static final List<String> SCHEMA_ORDER = Arrays.asList(
            "name", "is_a", "part_of", "treats", "causes", "contains", "located_in", "author", "defined_as", "date");
}
