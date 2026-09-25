package app.dilmun.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Everything derived from the log. Rebuilt by replay at every startup and
 * never stored as truth. Events for one fact are ordered by replay key, not by
 * arrival, so a view updated as transactions arrive lands where replay does.
 */
public final class State {

    /** A replay key: (hlc wall, hlc counter, portal, tx id). */
    public static final class At implements Comparable<At> {
        final long wall, counter;
        final String portal, id;

        At(Map<String, Object> tx) {
            wall = Tx.wall(tx); counter = Tx.counter(tx); portal = Tx.portal(tx); id = Tx.id(tx);
        }

        @Override public int compareTo(At o) {
            int c = Long.compare(wall, o.wall);
            if (c != 0) return c;
            c = Long.compare(counter, o.counter);
            if (c != 0) return c;
            c = portal.compareTo(o.portal);
            return c != 0 ? c : id.compareTo(o.id);
        }
    }

    public final Map<String, Map<String, Object>> schema = Policy.defaultSchema();
    public int schemaVersion = 0;
    public boolean paused = false;
    public final TreeMap<String, Map<String, Object>> openDirectives = new TreeMap<>();
    public final Map<String, At> approvals = new HashMap<>();
    public final Map<String, At> denials = new HashMap<>();                  // fact key → the steward's latest denial
    public final TreeMap<String, Map<String, Object>> corrections = new TreeMap<>(); // tx id → the steward's corrected fact
    public final List<Map<String, Object>> decisions = new ArrayList<>();
    public final LinkedHashMap<String, Map<String, Object>> datoms = new LinkedHashMap<>();
    public final Map<String, Object[]> lastEvent = new HashMap<>();          // fact key → {op, At}
    public final Map<String, TreeMap<Long, List<String>>> skills = Policy.defaultSkills();
    public final TreeMap<String, String> places = new TreeMap<>();            // portal|source → content hash
    public final TreeMap<String, String> placeNames = new TreeMap<>();        // source → display name
    public final List<Map<String, Object>> results = new ArrayList<>();     // assert headers, newest last
    private final Map<String, String> parent = new HashMap<>();
    public int applied = 0;

    public static State replay(List<Map<String, Object>> txs) {
        State st = new State();
        for (Map<String, Object> tx : Tx.sorted(txs, Tx.REPLAY)) st.apply(tx);
        return st;
    }

    // ------------------------------------------------------------ identity

    public String find(String e) {
        while (parent.containsKey(e) && !parent.get(e).equals(e)) e = parent.get(e);
        return e;
    }

    private void union(String a, String b) {
        String ra = find(a), rb = find(b);
        if (ra.equals(rb)) return;
        String lo = ra.compareTo(rb) < 0 ? ra : rb, hi = lo.equals(ra) ? rb : ra;
        parent.put(hi, lo);
        if (!parent.containsKey(lo)) parent.put(lo, lo);
    }

    /** Entity ID from an identity. Names are compared case- and spacing-insensitively. */
    public static String identId(String attr, Object value) {
        Object v = value instanceof String ? norm((String) value) : value;
        return "e:" + Crypto.H(Arrays.asList("ident", attr, v)).substring(0, 24);
    }

    public static String norm(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    public String factKey(String tier, Map<String, Object> d) {
        return "k:" + Crypto.H(Arrays.asList(tier, find((String) d.get("e")), d.get("a"), d.get("v"), d.get("ctx"))).substring(0, 32);
    }

    // ------------------------------------------------------------ apply

    @SuppressWarnings("unchecked")
    public void apply(Map<String, Object> tx) {
        applied++;
        Map<String, Object> h = Tx.header(tx), p = Tx.payload(tx);
        At at = new At(tx);
        String k = Tx.kind(tx);
        switch (k) {
            case "schema":
                schema.putAll((Map<String, Map<String, Object>>) (Map<?, ?>) p.get("attrs"));
                schemaVersion++;
                break;
            case "pause": paused = true; break;
            case "resume": paused = false; break;
            case "approve":
                for (Object key : (List<Object>) p.get("keys")) {
                    At cur = approvals.get(key);
                    if (cur == null || at.compareTo(cur) > 0) approvals.put((String) key, at);
                }
                break;
            case "deny":
                for (Object key : (List<Object>) p.get("keys")) deny((String) key, at);
                break;
            case "correct": {
                Map<String, Object> c = new TreeMap<>(p);
                c.put("at", at);
                c.put("tx", Tx.id(tx));
                corrections.put(Tx.id(tx), c);
                deny((String) p.get("from"), at);
                break;
            }
            case "skill": {
                TreeMap<Long, List<String>> vs = skills.get(p.get("name"));
                if (vs == null) { vs = new TreeMap<>(); skills.put((String) p.get("name"), vs); }
                vs.put(((Number) p.get("version")).longValue(), new ArrayList<>((List<String>) (List<?>) p.get("tools")));
                break;
            }
            case "directive": {
                Map<String, Object> d = new TreeMap<>(p);
                d.put("portal", Tx.portal(tx));
                d.put("issued", Tx.wall(tx));
                openDirectives.put((String) p.get("id"), d);
                break;
            }
            case "expire":
                openDirectives.remove(h.get("directive"));
                break;
            case "map": {
                Map<String, Object> pl = (Map<String, Object>) p.get("places");
                for (Map.Entry<String, Object> e : pl.entrySet()) places.put(Tx.portal(tx) + "|" + e.getKey(), (String) e.getValue());
                Object names = p.get("names");
                if (names instanceof Map)
                    for (Map.Entry<String, Object> e : ((Map<String, Object>) names).entrySet()) placeNames.put(e.getKey(), (String) e.getValue());
                break;
            }
            case "retract":
                for (Object fk : (List<Object>) p.get("keys")) event((String) fk, "retract", at);
                break;
            case "assert": case "promote": case "dream": {
                if ("assert".equals(k)) {
                    openDirectives.remove(h.get("directive"));
                    Map<String, Object> r = new TreeMap<>(h);
                    r.put("id", Tx.id(tx));
                    if (p != null && !Boolean.TRUE.equals(p.get("withheld"))) {
                        r.put("accepted", countFacts((List<Object>) p.get("datoms")));
                        Object rej = p.get("rejected");
                        r.put("rejected", rej == null ? Collections.emptyList() : rej);
                    }
                    results.add(r);
                }
                if ("promote".equals(k)) decisions.add((Map<String, Object>) p.get("decision"));
                if (p == null || Boolean.TRUE.equals(p.get("withheld"))) break;
                for (Object o : (List<Object>) p.get("datoms")) {
                    Map<String, Object> d0 = (Map<String, Object>) o;
                    if (Boolean.TRUE.equals(d0.get("excised"))) continue;
                    Map<String, Object> d = new TreeMap<>(d0);
                    d.put("tier", Tx.tier(tx));
                    d.put("t", Tx.wall(tx));
                    d.put("source", h.get("source"));
                    d.put("tx", Tx.id(tx));
                    datoms.put((String) d.get("id"), d);
                    Map<String, Object> att = schema.get(d.get("a"));
                    if (att != null && "identity".equals(att.get("unique")))
                        union((String) d.get("e"), identId((String) d.get("a"), d.get("v")));
                    event(factKey(Tx.tier(tx), d), "assert", at);
                }
                break;
            }
            default:
                break;   // genesis, admit: trust lives in the store
        }
    }

    private void deny(String key, At at) {
        At cur = denials.get(key);
        if (cur == null || at.compareTo(cur) > 0) denials.put(key, at);
    }

    /** Denied by the steward, and not approved since. The gate never promotes it, whatever the support. */
    public boolean denied(String key) {
        At d = denials.get(key), a = approvals.get(key);
        return d != null && (a == null || a.compareTo(d) < 0);
    }

    /** Approved by the steward, and not denied since. */
    public boolean approved(String key) {
        At d = denials.get(key), a = approvals.get(key);
        return a != null && (d == null || a.compareTo(d) > 0);
    }

    private static long countFacts(List<Object> datoms) {
        long n = 0;
        if (datoms == null) return 0;
        for (Object o : datoms) if (!"name".equals(((Map<?, ?>) o).get("a"))) n++;
        return n;
    }

    private void event(String fk, String op, At at) {
        Object[] cur = lastEvent.get(fk);
        if (cur == null || at.compareTo((At) cur[1]) > 0) lastEvent.put(fk, new Object[]{op, at});
    }

    // ------------------------------------------------------------ reads

    public boolean alive(Map<String, Object> d) {
        Object[] ev = lastEvent.get(factKey((String) d.get("tier"), d));
        return ev != null && "assert".equals(ev[0]);
    }

    public List<Map<String, Object>> tier(String name) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : datoms.values()) if (name.equals(d.get("tier")) && alive(d)) out.add(d);
        return out;
    }

    public List<Map<String, Object>> episodeDatoms() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> d : datoms.values()) if (Tx.isEpisode((String) d.get("tier")) && alive(d)) out.add(d);
        return out;
    }

    /** Entity → display name, from any live name fact. */
    public Map<String, String> names() {
        Map<String, String> out = new HashMap<>();
        for (Map<String, Object> d : datoms.values())
            if ("name".equals(d.get("a")) && alive(d)) out.put(find((String) d.get("e")), (String) d.get("v"));
        return out;
    }

    public String placeHash(String portal, String source) { return places.get(portal + "|" + source); }

    public List<String> allowedTools(String skill, long version) {
        TreeMap<Long, List<String>> vs = skills.get(skill);
        if (vs == null || !vs.containsKey(version)) return Collections.emptyList();
        List<String> out = new ArrayList<>(vs.get(version));
        out.retainAll(vs.get(vs.lastKey()));
        return out;
    }

    /** K = C ∘ F over culture. Keys are (entity, attribute, context). */
    public TreeMap<String, Object> settled() {
        Map<String, List<Map<String, Object>>> groups = new TreeMap<>();
        for (Map<String, Object> d : tier("culture")) {
            String key = Json.canon(Arrays.asList(find((String) d.get("e")), d.get("a"), d.get("ctx")));
            List<Map<String, Object>> g = groups.get(key);
            if (g == null) { g = new ArrayList<>(); groups.put(key, g); }
            g.add(d);
        }
        TreeMap<String, Object> out = new TreeMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : groups.entrySet()) {
            List<Map<String, Object>> ds = e.getValue();
            Map<String, Object> att = schema.get(ds.get(0).get("a"));
            if (att == null || "many".equals(att.get("card"))) {
                TreeSet<String> vals = new TreeSet<>();
                for (Map<String, Object> d : ds) vals.add(Json.canon(d.get("v")));
                out.put(e.getKey(), new ArrayList<>(vals));
            } else {
                Map<String, Object> best = null;
                for (Map<String, Object> d : ds) if (best == null || orderCompare(d, best, att) < 0) best = d;
                out.put(e.getKey(), Json.canon(best));
            }
        }
        return out;
    }

    /** Strict total order used by C. Content only, so it never depends on arrival. */
    static int orderCompare(Map<String, Object> a, Map<String, Object> b, Map<String, Object> att) {
        int ra = "dream".equals(a.get("tier")) ? 1 : 0, rb = "dream".equals(b.get("tier")) ? 1 : 0;
        if (ra != rb) return Integer.compare(ra, rb);                         // dreams never outrank observations
        if ("state".equals(att.get("kind"))) {
            int c = Long.compare(num(b.get("vf")), num(a.get("vf")));        // newer valid time first
            if (c != 0) return c;
        } else {
            int c = Long.compare(num(b.get("support")), num(a.get("support")));
            if (c != 0) return c;
        }
        int c = Long.compare(num(b.get("nu")), num(a.get("nu")));
        if (c != 0) return c;
        return ((String) a.get("id")).compareTo((String) b.get("id"));
    }

    private static long num(Object o) { return o == null ? 0 : ((Number) o).longValue(); }

    /** Everything that must agree across portals. */
    public String stateHash() {
        List<String> dec = new ArrayList<>();
        for (Map<String, Object> d : decisions) dec.add(Json.canon(d));
        Collections.sort(dec);
        Map<String, Object> skillsOut = new TreeMap<>();
        for (Map.Entry<String, TreeMap<Long, List<String>>> e : skills.entrySet()) {
            Map<String, Object> vs = new TreeMap<>();
            for (Map.Entry<Long, List<String>> v : e.getValue().entrySet()) vs.put(String.valueOf(v.getKey()), v.getValue());
            skillsOut.put(e.getKey(), vs);
        }
        Map<String, Object> all = Tx.m(
                "K", settled(),
                "decisions", dec,
                "paused", paused,
                "schema", (long) schemaVersion,
                "approvals", new ArrayList<>(new TreeSet<>(approvals.keySet())),
                "directives", new ArrayList<>(openDirectives.keySet()),
                "skills", skillsOut,
                "places", new TreeMap<>(places));
        // only when used, so a log without them keeps the state hash it always had
        if (!denials.isEmpty()) all.put("denials", new ArrayList<>(new TreeSet<>(denials.keySet())));
        if (!corrections.isEmpty()) all.put("corrections", new ArrayList<>(corrections.keySet()));
        return Crypto.H(all);
    }
}
