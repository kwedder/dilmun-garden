package app.dilmun.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One portal on one device: its store, its arbiters, and (in this build) the
 * steward's key as well. Every method that changes anything commits a signed
 * transaction; every decision reads state rebuilt from the log.
 */
public final class Engine {

    /** Where transactions are kept between runs (SQLite on Android). */
    public interface LogBackend {
        List<Map<String, Object>> loadTxs();
        List<Map<String, Object>> loadProofs();
        void putTx(Map<String, Object> tx);
        void putProof(Map<String, Object> proof);
    }

    /** The sources this device can read: a folder the user picked, or the bundled samples. */
    public interface Sources {
        /** [{id, name, hash}] for every readable text source. */
        List<Map<String, Object>> list();
        /** The text of one source, or null if it is not available here. */
        String read(String id);
    }

    public interface Clock { long now(); }

    public static final Clock SYSTEM_CLOCK = new Clock() {
        @Override public long now() { return System.currentTimeMillis(); }
    };

    public final String portal;
    private final LogBackend backend;
    private final Crypto.Signer portalKey, stewardKey;
    private final Clock clock;
    private final Agent agent;
    private Store store = new Store();
    private State cached;
    private long lastWall = 0, lastCounter = 0;
    private final Object traceLock = new Object();
    private final ArrayDeque<Map<String, Object>> trace = new ArrayDeque<>();
    private long traceSeq = 0;
    private volatile Runnable traceListener;

    private Engine(LogBackend backend, Crypto.Signer portalKey, Crypto.Signer stewardKey, Clock clock, Agent agent) {
        this.backend = backend;
        this.portalKey = portalKey;
        this.stewardKey = stewardKey;
        this.clock = clock;
        this.agent = agent;
        this.portal = portalName(portalKey.pub());
    }

    public static String portalName(String pub) { return "p-" + Crypto.sha256(Crypto.unhex(pub)).substring(0, 8); }

    /** Startup: load and re-verify the whole log, then make sure this device is part of the network. */
    public static Engine open(LogBackend backend, Crypto.Signer portalKey, Crypto.Signer stewardKey, Clock clock, Agent agent) {
        Engine e = new Engine(backend, portalKey, stewardKey, clock, agent);
        e.store = load(backend);
        for (Map<String, Object> t : e.store.all()) e.seen(t);
        e.note("replay", "Rebuilt state from " + plural(e.store.size(), "transaction", "transactions") + " on disk",
                Tx.m("txs", (long) e.store.size()));
        if (e.store.genesisId() == null) {
            e.commit(stewardKey, Tx.STEWARD, "genesis", "system", Tx.m(
                    "steward", stewardKey.pub(),
                    "roles", Tx.m(stewardKey.pub(), "steward", portalKey.pub(), "portal"),
                    "network", "dilmun",
                    "created", clock.now()), null);
        } else if (!"steward".equals(e.store.role(stewardKey.pub()))) {
            throw new Store.Rejected("this log was started by a different steward key than the one on this device");
        } else if (e.store.role(portalKey.pub()) == null) {
            e.commit(stewardKey, Tx.STEWARD, "admit", "system", Tx.m("pub", portalKey.pub(), "role", "portal"), null);
        }
        return e;
    }

    private static Store load(LogBackend backend) {
        Store s = new Store();
        for (Map<String, Object> t : Tx.sorted(backend.loadTxs(), Tx.LOAD)) s.append(t);
        for (Map<String, Object> p : backend.loadProofs()) s.addProof(p);
        return s;
    }

    // ------------------------------------------------------------ commit

    private void seen(Map<String, Object> t) {
        long w = Tx.wall(t), c = Tx.counter(t);
        if (w > lastWall || (w == lastWall && c > lastCounter)) { lastWall = w; lastCounter = c; }
    }

    private long[] nextHlc() {
        long now = clock.now();
        long wall = Math.max(now, lastWall);
        long counter = wall == lastWall ? lastCounter + 1 : 0;
        return new long[]{wall, counter};
    }

    private Map<String, Object> commit(Crypto.Signer key, String chain, String kind, String tier,
                                       Map<String, Object> payload, Map<String, Object> meta) {
        Map<String, Object> tx = Tx.make(key, chain, store.tip(chain), nextHlc(), kind, tier, payload, meta);
        boolean steward = Tx.STEWARD.equals(chain);
        note("sign", "Signed " + kind + " with the " + (steward ? "steward's" : "portal's") + " key",
                Tx.m("by", steward ? "steward" : "portal", "kind", kind));
        try {
            store.append(tx);
        } finally {
            for (Map<String, Object> p : store.forkProofs()) backend.putProof(p);
        }
        backend.putTx(tx);
        seen(tx);
        cached = null;
        note("commit", "Committed #" + store.size() + " " + kind + " to " + tier,
                Tx.m("kind", kind, "tier", tier, "n", (long) store.size(), "id", Tx.id(tx).substring(0, 10)));
        return tx;
    }

    // ------------------------------------------------------------ trace

    /**
     * What the engine just did, step by step, for the live map and the activity
     * card. Kept in memory only (the log is the record; this is a view of work as
     * it happens), and capped, so a screen that never reads it costs nothing but
     * a little memory. It has its own lock, so the screens can read it while a
     * slow agent is working.
     */
    public List<Object> trace(long since) {
        List<Object> out = new ArrayList<>();
        synchronized (traceLock) {
            for (Map<String, Object> ev : trace) if (((Number) ev.get("seq")).longValue() > since) out.add(ev);
        }
        return out;
    }

    /** Called after each new trace event, on whichever thread recorded it. */
    public void onTrace(Runnable listener) { traceListener = listener; }

    private void note(String step, String text, Map<String, Object> data) {
        Map<String, Object> ev = new TreeMap<>(data);
        ev.put("step", step);
        ev.put("text", text);
        synchronized (traceLock) {
            ev.put("seq", ++traceSeq);
            ev.put("t", clock.now());
            trace.addLast(ev);
            while (trace.size() > Policy.TRACE_KEEP) trace.removeFirst();
        }
        Runnable l = traceListener;
        if (l != null) l.run();
    }

    /** Steps an agent or the screens report while they work: never written to the log. */
    public static final List<String> WORK_STEPS = Collections.unmodifiableList(Arrays.asList(
            "passage", "passage-done", "bulk", "model"));

    /** Record a step taken outside the engine (a passage read, a bulk run moving on, a model loaded). */
    public void noteWork(String step, String text, Map<String, Object> data) {
        if (!WORK_STEPS.contains(step)) throw new IllegalArgumentException("not a work step: " + step);
        note(step, text, data == null ? Tx.m() : data);
    }

    private interface Body<T> { T run(); }

    /** A request from the screens: traced from the hand-off to the answer, refusals included. */
    private <T> T request(String what, boolean steward, Body<T> body) {
        note("request", (steward ? "Steward: " : "Request: ") + what, Tx.m("what", what, "steward", steward));
        try {
            T r = body.run();
            note("answer", "Answered: " + what, Tx.m("what", what, "steward", steward));
            return r;
        } catch (Store.Rejected e) {
            note("refuse", "Refused: " + e.getMessage(), Tx.m("what", what, "reason", String.valueOf(e.getMessage())));
            throw e;
        }
    }

    private static String plural(long n, String one, String many) { return n + " " + (n == 1 ? one : many); }

    public synchronized State state() {
        if (cached == null) cached = State.replay(store.valid());
        return cached;
    }

    private void live() {
        if (store.frozen(portal)) throw new Store.Rejected("frozen: this portal's chain forked; the steward must re-key it");
    }

    private void notPaused(State st) {
        if (st.paused) throw new Store.Rejected("paused by the steward");
    }

    // ------------------------------------------------------------ workspace map

    /** Record what each source holds now. Commits only when something changed. */
    public synchronized Map<String, Object> scan(Sources src) {
        return request("scan sources", false, () -> doScan(src));
    }

    private Map<String, Object> doScan(Sources src) {
        live();
        State st = state();
        List<Map<String, Object>> docs = src.list();
        Map<String, Object> places = new TreeMap<>(), names = new TreeMap<>();
        for (Map<String, Object> d : docs) {
            String id = (String) d.get("id"), hash = (String) d.get("hash");
            if (!hash.equals(st.placeHash(portal, id))) {
                places.put(id, hash);
                names.put(id, d.get("name"));
            }
        }
        note("scan", "Scanned " + plural(docs.size(), "source", "sources") + " · " + places.size() + " new or changed",
                Tx.m("total", (long) docs.size(), "changed", (long) places.size()));
        if (!places.isEmpty()) commit(portalKey, portal, "map", "system", Tx.m("places", places, "names", names), null);
        return Tx.m("changed", (long) places.size(), "total", (long) docs.size());
    }

    // ------------------------------------------------------------ directives

    public synchronized String issue(String sourceId, long budget) {
        live();
        State st = state();
        notPaused(st);
        int mine = 0;
        for (Map<String, Object> d : st.openDirectives.values()) if (portal.equals(d.get("portal"))) mine++;
        if (mine >= Policy.OPEN_LIMIT) throw new Store.Rejected("open directive limit reached; expire stale directives first");
        String digest = st.placeHash(portal, sourceId);
        if (digest == null) throw new Store.Rejected("source is not in the workspace map");
        TreeMap<Long, List<String>> vs = st.skills.get("ingest");
        if (vs == null) throw new Store.Rejected("skill is not in the registry");
        note("steer", "Steered: skill ingest v" + vs.lastKey() + " · source " + nameOf(st, sourceId),
                Tx.m("skill", "ingest", "version", vs.lastKey(), "source", nameOf(st, sourceId)));
        long now = nextHlc()[0];
        String did = "dir:" + Crypto.H(Arrays.asList(portal, now, sourceId, (long) store.size())).substring(0, 16);
        commit(portalKey, portal, "directive", "system", Tx.m(
                "id", did, "task", "extract", "skill", "ingest", "skill_version", vs.lastKey(),
                "source", sourceId, "source_hash", digest, "budget", budget,
                "expires", now + Policy.TTL_MS), null);
        return did;
    }

    /**
     * Deploy an agent on one directive, check what it proposes, and commit one result.
     *
     * Three steps, so a slow model never holds the engine: the checks and the
     * source read happen under the engine's lock, the agent runs without it,
     * and the commit takes the lock again and re-checks everything that could
     * have changed meanwhile (pause, fork, the directive, the skill).
     */
    public Map<String, Object> run(String did, Sources src, Agent with) {
        Object[] prep = prepareRun(did, src);
        Map<String, Object> proposal = with.propose(new TreeMap<>(castMap(prep[0])), (String) prep[1]);   // the only agent call
        Object fs = proposal.get("facts");
        long n = fs instanceof List ? ((List<?>) fs).size() : 0;
        note("propose", "Agent proposed " + plural(n, "fact", "facts"), Tx.m("facts", n));
        return finishRun(did, (String) prep[1], proposal, with);
    }

    public Map<String, Object> run(String did, Sources src) { return run(did, src, agent); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) { return (Map<String, Object>) o; }

    private synchronized Object[] prepareRun(String did, Sources src) {
        live();
        State st = state();
        notPaused(st);
        Map<String, Object> d = st.openDirectives.get(did);
        if (d == null || !portal.equals(d.get("portal"))) throw new Store.Rejected("no such open directive on this portal");
        if (nextHlc()[0] >= ((Number) d.get("expires")).longValue()) throw new Store.Rejected("directive expired");
        String source = (String) d.get("source");
        note("deploy", "Deployed the agent on " + did.substring(4, 12) + " · budget " + d.get("budget"),
                Tx.m("directive", did, "budget", d.get("budget")));
        String text = src.read(source);
        if (text == null) throw new Store.Rejected("source is not available on this device");
        if (!Crypto.textHash(text).equals(d.get("source_hash"))) throw new Store.Rejected("source changed since the directive; rescan");
        note("read", "Agent read " + nameOf(st, source) + " · " + plural(text.length(), "character", "characters"),
                Tx.m("source", nameOf(st, source), "chars", (long) text.length()));
        return new Object[]{d, text};
    }

    @SuppressWarnings("unchecked")
    private synchronized Map<String, Object> finishRun(String did, String text, Map<String, Object> proposal, Agent with) {
        live();
        State st = state();
        notPaused(st);
        Map<String, Object> d = st.openDirectives.get(did);
        if (d == null || !portal.equals(d.get("portal"))) throw new Store.Rejected("the directive closed while the agent was working");
        String source = (String) d.get("source");
        String skill = (String) d.get("skill");
        long version = ((Number) d.get("skill_version")).longValue();
        List<String> allowed = st.allowedTools(skill, version);
        Object toolsObj = proposal.get("tools"), factsObj = proposal.get("facts");
        List<Object> tools = toolsObj instanceof List ? (List<Object>) toolsObj : Collections.emptyList();
        List<Object> facts = factsObj instanceof List ? (List<Object>) factsObj : Collections.emptyList();
        long budget = ((Number) d.get("budget")).longValue();
        String nonce = Crypto.H(Arrays.asList(portal, did));

        List<Object> datoms = new ArrayList<>(), rejected = new ArrayList<>();
        String badTool = null;
        for (Object t : tools) if (!allowed.contains(t)) { badTool = String.valueOf(t); break; }
        long accepted = 0;
        for (int i = 0; i < facts.size(); i++) {
            Map<String, Object> f = facts.get(i) instanceof Map ? (Map<String, Object>) facts.get(i) : Collections.<String, Object>emptyMap();
            String reason = null;
            String[] fixed = null;                                     // the fact finished from its sentence, if it was cut short
            Object qo = f.get("quote"), ao = f.get("a"), vo = f.get("v"), io = f.get("ident"), no = f.get("nu");
            Map<String, Object> q = qo instanceof Map ? (Map<String, Object>) qo : Collections.<String, Object>emptyMap();
            String a = ao instanceof String ? (String) ao : String.valueOf(ao);
            List<Object> ident = io instanceof List ? (List<Object>) io : Collections.emptyList();
            if (badTool != null) reason = "tool not allowed by the skill: " + badTool;
            else if (!(q.get("start") instanceof Number) || !(q.get("end") instanceof Number) || !(q.get("text") instanceof String)
                    || !quoteMatches(text, ((Number) q.get("start")).intValue(), ((Number) q.get("end")).intValue(), (String) q.get("text")))
                reason = "quote does not match the source";
            else if (!st.schema.containsKey(a) || "name".equals(a)) reason = "attribute not in the schema: " + a;
            else if (ident.size() != 2 || !"name".equals(ident.get(0)) || !(ident.get(1) instanceof String)
                    || ((String) ident.get(1)).trim().isEmpty() || !(vo instanceof String) || ((String) vo).trim().isEmpty())
                reason = "the fact is incomplete";
            else if ((reason = ground((String) ident.get(1), a, (String) vo, text, q)) != null
                    && (fixed = repair((String) ident.get(1), a, (String) vo, text, q)) != null) reason = null;
            if (reason == null && accepted >= budget) reason = "over budget";      // a repaired fact counts too
            if (reason != null) {
                rejected.add(Tx.m("i", (long) i, "a", a, "reason", reason));
                if (i < FACT_NOTES) note("fact", "Refused " + factLine(ident, a, vo) + " · " + reason,
                        Tx.m("kept", false, "reason", reason));
                continue;
            }
            String name = ((String) ident.get(1)).trim(), val = ((String) vo).trim();
            if (fixed != null) { name = fixed[0]; val = fixed[1]; }
            if (((String) q.get("text")).indexOf('|') < 0) {                 // prose: write the fact as concepts
                String sentence = sentenceOf(text, q);
                name = Grounding.concept(name, sentence);
                if (!"date".equals(a) && !"defined_as".equals(a)) val = Grounding.concept(val, sentence);
            }
            if (i < FACT_NOTES) note("fact", "Kept " + factLine(ident, a, vo) + (fixed == null ? "" : " · finished from its sentence as "
                    + fixed[0] + " · " + fixed[1]), Tx.m("kept", true, "repaired", fixed != null));
            long nu = no instanceof Number ? Math.max(0, Math.min(1000, ((Number) no).longValue())) : 500L;
            String e = State.identId("name", name);
            Object v = val;
            boolean ref = Boolean.TRUE.equals(st.schema.get(a).get("ref"));
            if (ref) v = Tx.m("ref", State.identId("name", val));
            long vf = nextHlc()[0];
            datoms.add(datom("d:" + Crypto.H(Arrays.asList(nonce, (long) i)).substring(0, 24), e, a, v, nu, vf, q));
            datoms.add(datom("d:" + Crypto.H(Arrays.asList(nonce, (long) i, "name")).substring(0, 24), e, "name", name, 1000L, vf, q));
            if (ref) {                                                   // the value is an entity too
                datoms.add(datom("d:" + Crypto.H(Arrays.asList(nonce, (long) i, "vname")).substring(0, 24),
                        State.identId("name", val), "name", val, 1000L, vf, q));
            }
            accepted++;
        }
        if (facts.size() > FACT_NOTES)
            note("fact", "…and " + plural(facts.size() - FACT_NOTES, "more fact", "more facts") + " checked", Tx.m("more", (long) (facts.size() - FACT_NOTES)));
        note("check", "Checked quotes, grounding, schema, tools, budget: " + accepted + " kept · " + rejected.size() + " refused",
                Tx.m("accepted", accepted, "rejected", (long) rejected.size()));
        Map<String, Object> tx = commit(portalKey, portal, "assert", "episode:" + did,
                Tx.m("proposal", proposal, "datoms", datoms, "rejected", rejected),
                Tx.m("directive", did, "agent", with.id(), "skill", skill, "skill_version", version,
                        "skill_current", st.skills.get(skill).lastKey(), "model_hash", with.id(),
                        "source", source, "source_hash", d.get("source_hash")));
        return Tx.m("tx", Tx.id(tx), "accepted", accepted, "rejected", rejected, "agent", with.id());
    }

    /** Facts checked one by one in the trace; the rest are summed up, so a big result can't flood it. */
    static final int FACT_NOTES = 24;

    private static String factLine(List<Object> ident, String a, Object v) {
        String e = ident.size() == 2 ? String.valueOf(ident.get(1)).trim() : "?";
        return e + " · " + a + " · " + (v == null ? "?" : String.valueOf(v).trim());
    }

    /** Grounding against the quote's sentence in the source, and the sentence before it. */
    private static String ground(String entity, String a, String value, String text, Map<String, Object> q) {
        int start = ((Number) q.get("start")).intValue(), end = ((Number) q.get("end")).intValue();
        int s0 = sentenceStart(text, start), s1 = sentenceEnd(text, end);
        int b0 = s0 > 0 ? sentenceStart(text, s0 - 1) : s0;
        return Grounding.check(entity, a, value, (String) q.get("text"), text.substring(s0, s1), text.substring(b0, s0));
    }

    private static String[] repair(String entity, String a, String value, String text, Map<String, Object> q) {
        int start = ((Number) q.get("start")).intValue(), end = ((Number) q.get("end")).intValue();
        int s0 = sentenceStart(text, start), s1 = sentenceEnd(text, end);
        int b0 = s0 > 0 ? sentenceStart(text, s0 - 1) : s0;
        return Grounding.repair(entity, a, value, (String) q.get("text"), text.substring(s0, s1), text.substring(b0, s0));
    }

    private static String sentenceOf(String text, Map<String, Object> q) {
        int start = ((Number) q.get("start")).intValue(), end = ((Number) q.get("end")).intValue();
        return text.substring(sentenceStart(text, start), sentenceEnd(text, end));
    }

    private static int sentenceStart(String text, int i) {
        for (int j = i - 1; j >= 0; j--) {
            char c = text.charAt(j);
            if (c == '\n' && j > 0 && text.charAt(j - 1) == '\n') return j + 1;
            if ((c == '.' || c == '!' || c == '?' || c == '\n') && j + 1 < text.length() && Character.isWhitespace(text.charAt(j + 1)) && j + 1 <= i - 1)
                return j + 1;
        }
        return 0;
    }

    private static int sentenceEnd(String text, int i) {
        for (int j = Math.max(i, 1) - 1; j < text.length(); j++) {
            char c = text.charAt(j);
            if (j >= i && (c == '.' || c == '!' || c == '?') && (j + 1 == text.length() || Character.isWhitespace(text.charAt(j + 1)))) return j + 1;
            if (j >= i && c == '\n') return j;
        }
        return text.length();
    }

    private static boolean quoteMatches(String text, int start, int end, String quote) {
        return start >= 0 && end <= text.length() && start <= end && text.substring(start, end).equals(quote);
    }

    private static Map<String, Object> datom(String id, String e, String a, Object v, long nu, long vf, Map<String, Object> q) {
        return Tx.m("id", id, "e", e, "a", a, "v", v, "ctx", null, "nu", nu, "vf", vf, "vt", null,
                "quote", q, "origin", null, "origin_tx", null, "support", 1L);
    }

    /** Issue a directive for one source and run it straight away. */
    public Map<String, Object> extract(String sourceId, Sources src, Agent with) {
        return request("extract " + nameOf(state(), sourceId), false, () -> run(issue(sourceId, Policy.DEFAULT_BUDGET), src, with));
    }

    /**
     * Whether this source already has a result for exactly what it holds now.
     * A bulk run skips these; a source whose file changed since is extracted again.
     */
    public synchronized boolean extracted(String sourceId) {
        State st = state();
        String now = st.placeHash(portal, sourceId);
        if (now == null) return false;
        for (Map<String, Object> r : st.results)
            if (sourceId.equals(r.get("source")) && now.equals(r.get("source_hash"))) return true;
        return false;
    }

    public Map<String, Object> extract(String sourceId, Sources src) { return extract(sourceId, src, agent); }

    /**
     * The facts most related to a question, for Ask: settled facts (culture)
     * first, then facts still held at the gate, marked as unconfirmed so the
     * model and the reader can tell them apart. A plain word-overlap score over
     * entity, attribute and value (counted double) and the fact's quote; BM25
     * and Datalog come with the query slice. Reads only: nothing is committed,
     * but the read is traced, so the map and the activity card show it.
     */
    public synchronized List<Object> recall(String question, int limit) {
        Set<String> words = words(question);
        State st = state();
        Map<String, String> names = st.names();
        List<Object[]> scored = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> d : st.tier("culture")) {
            if ("name".equals(d.get("a"))) continue;
            String e = st.find((String) d.get("e"));
            String ent = names.containsKey(e) ? names.get(e) : e, val = display(st, names, d.get("v"));
            if (!seen.add(Json.canon(Arrays.asList(ent.toLowerCase(Locale.ROOT), d.get("a"), val.toLowerCase(Locale.ROOT))))) continue;
            Map<String, Object> row = recallRow(ent, d, val, ((Number) d.get("support")).longValue(), "settled", quoteOf(d));
            double score = score(words, row);
            if (score > 0) scored.add(new Object[]{score + 0.5, row});
        }
        Set<String> promoted = new HashSet<>();
        for (Map<String, Object> d : st.tier("culture")) promoted.add(st.factKey("culture", d));
        for (Map.Entry<String, List<Map<String, Object>>> g : groupEpisodes(st).entrySet()) {
            Map<String, Object> d = g.getValue().get(0);
            if ("name".equals(d.get("a")) || promoted.contains(g.getKey()) || st.denied(g.getKey())) continue;
            String e = st.find((String) d.get("e"));
            String ent = names.containsKey(e) ? names.get(e) : e, val = display(st, names, d.get("v"));
            if (!seen.add(Json.canon(Arrays.asList(ent.toLowerCase(Locale.ROOT), d.get("a"), val.toLowerCase(Locale.ROOT))))) continue;
            String best = quoteOf(d);                               // the quote, of all its sources', that fits the question best
            int bestHits = -1;
            for (Map<String, Object> x : g.getValue()) {
                Set<String> qw = words(quoteOf(x));
                int hits = 0;
                for (String w : words) if (qw.contains(w)) hits++;
                if (hits > bestHits) { bestHits = hits; best = quoteOf(x); }
            }
            Map<String, Object> row = recallRow(ent, d, val, distinctSources(g.getValue()), "held", best);
            double score = score(words, row);
            if (score > 0) scored.add(new Object[]{score, row});
        }
        Collections.sort(scored, (x, y) -> Double.compare((Double) y[0], (Double) x[0]));
        List<Object> out = new ArrayList<>();
        long settled = 0, held = 0;
        for (Object[] x : scored) {
            if (out.size() >= limit) break;
            out.add(x[1]);
            if ("settled".equals(castMap(x[1]).get("status"))) settled++; else held++;
        }
        note("recall", "Ask read memory: " + plural(settled, "settled fact", "settled facts") + " and "
                        + plural(held, "unconfirmed fact", "unconfirmed facts") + " match the question",
                Tx.m("settled", settled, "held", held));
        return out;
    }

    /** Longest quote shown to the model with a recalled fact. */
    static final int RECALL_QUOTE = 200;

    private static String quoteOf(Map<String, Object> d) {
        Object q = d.get("quote");
        return q instanceof Map && ((Map<?, ?>) q).get("text") instanceof String ? ((String) ((Map<?, ?>) q).get("text")).trim() : "";
    }

    private static Map<String, Object> recallRow(String ent, Map<String, Object> d, String val, long support, String status, String quote) {
        if (quote.length() > RECALL_QUOTE) quote = quote.substring(0, RECALL_QUOTE - 1) + "…";
        return Tx.m("entity", ent, "a", d.get("a"), "v", val, "support", support, "nu", d.get("nu"),
                "status", status, "quote", quote);
    }

    private static double score(Set<String> words, Map<String, Object> row) {
        Set<String> fact = words(row.get("entity") + " " + String.valueOf(row.get("a")).replace('_', ' ') + " " + row.get("v"));
        Set<String> quote = words(String.valueOf(row.get("quote")));
        double s = 0;
        for (String w : words) {
            if (fact.contains(w)) s += 2;
            else if (quote.contains(w)) s += 1;
        }
        return s;
    }

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "the", "and", "for", "are", "was", "what", "which", "who", "how", "does", "did", "that", "this", "with",
            "from", "about", "into", "can", "its", "has", "have", "why", "when", "where", "there", "their", "any", "not"));

    static Set<String> words(String s) {
        Set<String> out = new HashSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() < 3 || STOP.contains(w)) continue;
            out.add(w.endsWith("s") && w.length() > 4 ? w.substring(0, w.length() - 1) : w);
        }
        return out;
    }

    /** Startup step: close this portal's directives that have passed their deadline. */
    public synchronized long reconcile() {
        return request("expire stale directives", false, this::doReconcile);
    }

    private long doReconcile() {
        live();
        long n = 0;
        for (Map.Entry<String, Map<String, Object>> e : new TreeMap<>(state().openDirectives).entrySet()) {
            Map<String, Object> d = e.getValue();
            if (portal.equals(d.get("portal")) && nextHlc()[0] >= ((Number) d.get("expires")).longValue()) {
                commit(portalKey, portal, "expire", "system", Tx.m("directive", e.getKey()), Tx.m("directive", e.getKey()));
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------ promotion gate

    /** Copy a fact into culture once k distinct sources agree, or the steward approved it. */
    public synchronized long gate() {
        return request("run the gate", false, this::doGate);
    }

    private long doGate() {
        live();
        State st = state();
        notPaused(st);
        List<Object> withdrawn = new ArrayList<>();                 // settled facts the steward has since denied
        for (Map<String, Object> d : st.tier("culture")) {
            String key = st.factKey("culture", d);
            if (!"name".equals(d.get("a")) && st.denied(key) && !withdrawn.contains(key)) withdrawn.add(key);
        }
        if (!withdrawn.isEmpty()) {
            Collections.sort(withdrawn, (x, y) -> ((String) x).compareTo((String) y));
            note("retract", "Gate: withdrew " + plural(withdrawn.size(), "denied fact", "denied facts") + " from culture",
                    Tx.m("retracted", (long) withdrawn.size()));
            commit(portalKey, portal, "retract", "system", Tx.m("keys", withdrawn, "rule", "denied"), null);
            st = state();
        }
        Set<String> promoted = new HashSet<>();
        for (Map<String, Object> d : st.tier("culture")) promoted.add(st.factKey("culture", d));
        TreeMap<String, List<Map<String, Object>>> groups = groupEpisodes(st);
        long n = 0;
        for (Map.Entry<String, List<Map<String, Object>>> g : groups.entrySet()) {
            String key = g.getKey();
            if (promoted.contains(key) || st.denied(key)) continue;
            List<Map<String, Object>> ds = g.getValue();
            Object[] last = st.lastEvent.get(key);
            State.At after = last != null && "retract".equals(last[0]) ? (State.At) last[1] : null;
            State.At appr = st.approvals.get(key);
            if (after != null) {                                   // retracted: old evidence is spent
                List<Map<String, Object>> fresh = new ArrayList<>();
                for (Map<String, Object> d : ds)
                    if (((State.At) st.lastEvent.get(st.factKey((String) d.get("tier"), d))[1]).compareTo(after) > 0) fresh.add(d);
                if (fresh.isEmpty() && !(appr != null && appr.compareTo(after) > 0)) continue;
                if (!fresh.isEmpty()) ds = fresh;
            }
            long support = distinctSources(ds);
            boolean approved = st.approved(key) && (after == null || appr.compareTo(after) > 0);
            if (support < Policy.GATE_K && !approved) continue;
            Map<String, Object> rep = ds.get(0);
            for (Map<String, Object> d : ds) if (((String) d.get("id")).compareTo((String) rep.get("id")) < 0) rep = d;
            Map<String, Object> copy = new TreeMap<>();
            for (String f : Arrays.asList("e", "a", "v", "ctx", "nu", "vf", "vt", "quote")) copy.put(f, rep.get(f));
            copy.put("id", "d:" + Crypto.H(Arrays.asList("promote", rep.get("id"), store.tip(portal))).substring(0, 24));
            copy.put("origin", rep.get("id"));
            copy.put("origin_tx", rep.get("tx"));
            copy.put("derived_from", null);
            copy.put("support", support);
            note("promote", "Gate: " + factText(st, rep) + " · " + (approved && support < Policy.GATE_K ? "approved" : plural(support, "source", "sources")),
                    Tx.m("support", support, "approved", approved && support < Policy.GATE_K));
            commit(portalKey, portal, "promote", "culture", Tx.m(
                    "datoms", Collections.singletonList(copy),
                    "decision", Tx.m("key", key, "support", support,
                            "rule", approved && support < Policy.GATE_K ? "approved" : "auto")), null);
            n++;
        }
        n += promoteCorrections(st, promoted);
        long waiting = held().size();
        if (waiting > 0) note("hold", "Gate held " + plural(waiting, "fact", "facts") + " for more sources or approval", Tx.m("held", waiting));
        return n;
    }

    /** The steward's corrected facts go to culture as written, with the original's quote. */
    @SuppressWarnings("unchecked")
    private long promoteCorrections(State st, Set<String> promoted) {
        long n = 0;
        for (Map<String, Object> c : st.corrections.values()) {
            String name = (String) c.get("entity"), a = (String) c.get("a"), val = (String) c.get("v");
            String e = State.identId("name", name);
            boolean ref = refAttr(st, a);
            Object v = ref ? Tx.m("ref", State.identId("name", val)) : val;
            String key = correctedKey(st, name, a, val);
            if (promoted.contains(key) || st.denied(key)) continue;
            Object[] last = st.lastEvent.get(key);                  // withdrawn after this correction: back only if approved since
            if (last != null && "retract".equals(last[0]) && ((State.At) last[1]).compareTo((State.At) c.get("at")) > 0) {
                State.At appr = st.approvals.get(key);
                if (appr == null || appr.compareTo((State.At) last[1]) < 0) continue;
            }
            String tx = (String) c.get("tx");
            Object quote = c.get("quote");
            long vf = nextHlc()[0];
            List<Object> datoms = new ArrayList<>();
            Map<String, Object> copy = datom("d:" + Crypto.H(Arrays.asList("correct", tx)).substring(0, 24), e, a, v, 1000L, vf,
                    quote instanceof Map ? (Map<String, Object>) quote : null);
            copy.put("origin", tx);
            copy.put("edited", true);
            datoms.add(copy);
            datoms.add(datom("d:" + Crypto.H(Arrays.asList("correct", tx, "name")).substring(0, 24), e, "name", name, 1000L, vf, null));
            if (ref) datoms.add(datom("d:" + Crypto.H(Arrays.asList("correct", tx, "vname")).substring(0, 24),
                    State.identId("name", val), "name", val, 1000L, vf, null));
            note("promote", "Gate: " + name + " " + a + " " + val + " · edited by the steward", Tx.m("support", 1L, "approved", true));
            commit(portalKey, portal, "promote", "culture", Tx.m("datoms", datoms,
                    "decision", Tx.m("key", key, "support", 1L, "rule", "corrected", "from", c.get("from"))), null);
            promoted.add(key);
            n++;
        }
        return n;
    }

    private static boolean refAttr(State st, String a) {
        return st.schema.containsKey(a) && Boolean.TRUE.equals(st.schema.get(a).get("ref"));
    }

    /** The fact key a corrected fact will have in culture. */
    private static String correctedKey(State st, String entity, String a, String val) {
        Object v = refAttr(st, a) ? Tx.m("ref", State.identId("name", val)) : val;
        return st.factKey("culture", Tx.m("e", State.identId("name", entity), "a", a, "v", v, "ctx", null));
    }

    private static String factText(State st, Map<String, Object> d) {
        Map<String, String> names = st.names();
        String e = st.find((String) d.get("e"));
        String ent = names.containsKey(e) ? names.get(e) : e;
        return "name".equals(d.get("a")) ? "entity " + ent : ent + " " + d.get("a") + " " + display(st, names, d.get("v"));
    }

    private static TreeMap<String, List<Map<String, Object>>> groupEpisodes(State st) {
        TreeMap<String, List<Map<String, Object>>> groups = new TreeMap<>();
        for (Map<String, Object> d : st.episodeDatoms()) {
            String key = st.factKey("culture", d);
            List<Map<String, Object>> g = groups.get(key);
            if (g == null) { g = new ArrayList<>(); groups.put(key, g); }
            g.add(d);
        }
        return groups;
    }

    private static long distinctSources(List<Map<String, Object>> ds) {
        Set<Object> s = new HashSet<>();
        for (Map<String, Object> d : ds) s.add(d.get("source"));
        return s.size();
    }

    // ------------------------------------------------------------ enlisting a model

    /**
     * A model goes before the arbiters when it loads. They enlist it under the
     * hashes of the briefings it will be given (extraction, its output grammar,
     * reading), signed into the log, so every later result can be traced to the
     * exact instructions behind it. A model already enlisted under the same
     * briefings isn't enlisted again. Returns the briefings' hashes and the
     * arbiters' notes on the model's last results.
     */
    public synchronized Map<String, Object> present(String modelId) {
        return request("enlist " + modelId, false, () -> {
            live();
            List<String> fixed = Briefing.fixed(extractable());
            Map<String, Object> briefs = Tx.m("extract", Briefing.hash(fixed.get(0)), "grammar", Briefing.hash(fixed.get(1)),
                    "read", Briefing.hash(fixed.get(2)));
            State st = state();
            boolean again = briefs.equals(st.enlisted.get(modelId));
            if (!again) commit(portalKey, portal, "enlist", "system", Tx.m("model", modelId, "briefings", briefs), null);
            List<String> notes = notes(modelId);
            note("enlist", (again ? "Arbiters: model already enlisted under these briefings · " : "Arbiters enlisted the model · ")
                    + "briefing " + briefs.get("extract") + (notes.isEmpty() ? "" : " · " + plural(notes.size(), "note", "notes") + " from its last results"),
                    Tx.m("model", modelId, "again", again, "notes", (long) notes.size()));
            return Tx.m("model", modelId, "briefings", briefs, "notes", new ArrayList<Object>(notes));
        });
    }

    /** The schema attributes a model may report. */
    public static List<String> extractable() {
        List<String> out = new ArrayList<>(Policy.SCHEMA_ORDER);
        out.remove("name");
        return out;
    }

    /** How many of a model's last results the arbiters' notes look back over. */
    static final int NOTE_RESULTS = 5;

    /**
     * The arbiters' notes on a model's last results: the three reasons they
     * refused its facts most often, counted, and how many it got through. Only
     * the model's own facts count, not the ones the rules found beside them.
     * They go into its next briefing, so it gets its own record back each time.
     */
    @SuppressWarnings("unchecked")
    public synchronized List<String> notes(String modelId) {
        State st = state();
        Map<String, Long> why = new TreeMap<>();
        long refused = 0, total = 0;
        int seen = 0;
        for (int i = st.results.size() - 1; i >= 0 && seen < NOTE_RESULTS; i--) {
            Map<String, Object> r = st.results.get(i);
            if (!modelId.equals(r.get("agent"))) continue;
            seen++;
            Map<String, Object> tx = store.get((String) r.get("id"));
            Object prop = tx == null || Tx.payload(tx) == null ? null : Tx.payload(tx).get("proposal");
            List<Object> facts = prop instanceof Map && ((Map<String, Object>) prop).get("facts") instanceof List
                    ? (List<Object>) ((Map<String, Object>) prop).get("facts") : Collections.emptyList();
            Set<Long> mine = new HashSet<>();
            for (int k = 0; k < facts.size(); k++) if (!"rules".equals(((Map<String, Object>) facts.get(k)).get("by"))) { mine.add((long) k); total++; }
            for (Object o : (List<Object>) r.get("rejected")) {
                Map<String, Object> rj = (Map<String, Object>) o;
                if (!mine.contains(((Number) rj.get("i")).longValue())) continue;
                refused++;
                String reason = String.valueOf(rj.get("reason")).replaceAll(":.*|\"[^\"]*\"", "").replaceAll(" (between|that) .*", "").trim();
                why.merge(reason, 1L, Long::sum);
            }
        }
        List<String> out = new ArrayList<>();
        if (total == 0) return out;
        out.add((total - refused) + " of your last " + total + " facts were kept.");
        List<Map.Entry<String, Long>> top = new ArrayList<>(why.entrySet());
        Collections.sort(top, (x, y) -> Long.compare(y.getValue(), x.getValue()));
        for (int k = 0; k < Math.min(3, top.size()); k++) out.add(top.get(k).getValue() + " were refused: " + top.get(k).getKey() + ".");
        return out;
    }

    // ------------------------------------------------------------ steward

    public synchronized void approve(List<String> keys) {
        List<String> sorted = new ArrayList<>(keys);
        Collections.sort(sorted);
        request("approve " + plural(sorted.size(), "held fact", "held facts"), true,
                () -> commit(stewardKey, Tx.STEWARD, "approve", "system", Tx.m("keys", new ArrayList<Object>(sorted)), null));
    }

    /** The steward says these facts are wrong: the gate never promotes them, and withdraws them if settled. */
    public synchronized void deny(List<String> keys) {
        List<String> sorted = new ArrayList<>(keys);
        Collections.sort(sorted);
        request("deny " + plural(sorted.size(), "fact", "facts"), true,
                () -> commit(stewardKey, Tx.STEWARD, "deny", "system", Tx.m("keys", new ArrayList<Object>(sorted)), null));
    }

    /**
     * The steward rewrites a held or settled fact. The original is denied; the
     * gate puts the corrected fact in culture, keeping the original's quote so
     * it still points at where it came from.
     */
    public synchronized void correct(String key, String entity, String attribute, String value) {
        String e = entity == null ? "" : entity.trim(), v = value == null ? "" : value.trim();
        String a = attribute == null ? "" : attribute.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        request("edit a fact", true, () -> {
            State st = state();
            if (e.isEmpty() || v.isEmpty()) throw new Store.Rejected("an edited fact needs an entity and a value");
            if (!st.schema.containsKey(a) || "name".equals(a)) throw new Store.Rejected("attribute not in the schema: " + a);
            if (State.norm(e).equals(State.norm(v))) throw new Store.Rejected("the value repeats the entity");
            Object quote = null;
            for (Map<String, Object> d : st.datoms.values())
                if (key.equals(st.factKey("culture", d)) && d.get("quote") != null) { quote = d.get("quote"); break; }
            if (quote == null) throw new Store.Rejected("no such fact");
            if (key.equals(correctedKey(st, e, a, v))) throw new Store.Rejected("the edit changes nothing");
            return commit(stewardKey, Tx.STEWARD, "correct", "system",
                    Tx.m("from", key, "entity", e, "a", a, "v", v, "quote", quote), null);
        });
    }

    public synchronized void pause() { request("pause the arbiters", true, () -> commit(stewardKey, Tx.STEWARD, "pause", "system", Tx.m(), null)); }

    public synchronized void resume() { request("resume the arbiters", true, () -> commit(stewardKey, Tx.STEWARD, "resume", "system", Tx.m(), null)); }

    public synchronized void publishSkill(String name, long version, List<String> tools) {
        List<Object> t = new ArrayList<Object>(tools);
        commit(stewardKey, Tx.STEWARD, "skill", "system", Tx.m("name", name, "version", version, "tools", t), null);
    }

    // ------------------------------------------------------------ reads for the screens

    public synchronized Map<String, Object> summary() {
        State st = state();
        long mine = 0, mapped = 0;
        for (Map<String, Object> d : st.openDirectives.values()) if (portal.equals(d.get("portal"))) mine++;
        for (String k : st.places.keySet()) if (k.startsWith(portal + "|")) mapped++;
        return Tx.m(
                "portal", portal,
                "network", store.genesisId() == null ? "" : store.genesisId().substring(0, 12),
                "txs", (long) store.size(),
                "state", st.stateHash().substring(0, 12),
                "paused", st.paused,
                "frozen", store.frozen(portal),
                "sources", mapped,
                "open", mine,
                "culture", (long) memory("").size(),
                "held", (long) held().size(),
                "results", (long) st.results.size(),
                "skill", "ingest v" + st.skills.get("ingest").lastKey(),
                "tools", new ArrayList<Object>(st.skills.get("ingest").lastEntry().getValue()),
                "agent", agent.id(),
                "k", (long) Policy.GATE_K,
                "schema", new ArrayList<Object>(Policy.SCHEMA_ORDER));
    }

    public synchronized List<Object> sources() {
        State st = state();
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, String> e : st.places.entrySet()) {
            if (!e.getKey().startsWith(portal + "|")) continue;
            String id = e.getKey().substring(portal.length() + 1);
            String name = st.placeNames.containsKey(id) ? st.placeNames.get(id) : id;
            out.add(Tx.m("id", id, "name", name, "hash", e.getValue().substring(0, 12)));
        }
        Collections.sort(out, (x, y) -> ((String) ((Map<?, ?>) x).get("name")).compareToIgnoreCase((String) ((Map<?, ?>) y).get("name")));
        return out;
    }

    public synchronized List<Object> directives() {
        State st = state();
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> d : st.openDirectives.values()) {
            String s = (String) d.get("source");
            out.add(Tx.m("id", d.get("id"), "source", s, "name", nameOf(st, s), "portal", d.get("portal"),
                    "issued", d.get("issued"), "expires", d.get("expires"),
                    "stale", nextHlc()[0] >= ((Number) d.get("expires")).longValue()));
        }
        return out;
    }

    public synchronized List<Object> results(int limit) {
        State st = state();
        List<Object> out = new ArrayList<>();
        for (int i = st.results.size() - 1; i >= 0 && out.size() < limit; i--) {
            Map<String, Object> r = st.results.get(i);
            String s = (String) r.get("source");
            out.add(Tx.m("tx", r.get("id"), "directive", r.get("directive"), "source", s, "name", nameOf(st, s),
                    "accepted", r.containsKey("accepted") ? r.get("accepted") : 0L,
                    "rejected", r.containsKey("rejected") ? r.get("rejected") : Collections.emptyList(),
                    "wall", ((List<?>) r.get("hlc")).get(0), "agent", r.get("agent")));
        }
        return out;
    }

    private static String nameOf(State st, String source) {
        return st.placeNames.containsKey(source) ? st.placeNames.get(source) : source;
    }

    /** Settled facts, readable: entity · attribute · value, with support. */
    public synchronized List<Object> memory(String query) {
        State st = state();
        Map<String, String> names = st.names();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        TreeMap<String, Map<String, Object>> rows = new TreeMap<>();
        for (Map<String, Object> d : st.tier("culture")) {
            if ("name".equals(d.get("a"))) continue;
            String ent = names.containsKey(st.find((String) d.get("e"))) ? names.get(st.find((String) d.get("e"))) : (String) d.get("e");
            String val = display(st, names, d.get("v"));
            String row = ent + " " + d.get("a") + " " + val;
            if (!q.isEmpty() && !row.toLowerCase(Locale.ROOT).contains(q)) continue;
            String key = Json.canon(Arrays.asList(ent.toLowerCase(Locale.ROOT), d.get("a"), val));
            Map<String, Object> cur = rows.get(key);
            long sup = ((Number) d.get("support")).longValue();
            if (cur == null || ((Number) cur.get("support")).longValue() < sup)
                rows.put(key, Tx.m("entity", ent, "a", d.get("a"), "v", val, "support", sup, "nu", d.get("nu"),
                        "key", st.factKey("culture", d), "edited", Boolean.TRUE.equals(d.get("edited"))));
        }
        return new ArrayList<Object>(rows.values());
    }

    /** Facts seen in episodes that the gate is holding: too few sources and no approval yet. */
    public synchronized List<Object> held() {
        State st = state();
        Map<String, String> names = st.names();
        Set<String> promoted = new HashSet<>();
        for (Map<String, Object> d : st.tier("culture")) promoted.add(st.factKey("culture", d));
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> g : groupEpisodes(st).entrySet()) {
            Map<String, Object> d = g.getValue().get(0);
            if ("name".equals(d.get("a")) || promoted.contains(g.getKey())) continue;
            long support = distinctSources(g.getValue());
            if (support >= Policy.GATE_K || st.denied(g.getKey())) continue;
            String e = st.find((String) d.get("e"));
            out.add(Tx.m("key", g.getKey(), "entity", names.containsKey(e) ? names.get(e) : e,
                    "a", d.get("a"), "v", display(st, names, d.get("v")), "support", support,
                    "approved", st.approved(g.getKey()), "quote", quoteOf(d)));
        }
        return out;
    }

    /** Facts the steward denied and hasn't approved since, newest first, so a denial can be undone. */
    public synchronized List<Object> denied() {
        State st = state();
        Map<String, String> names = st.names();
        Map<String, Map<String, Object>> byKey = new HashMap<>();
        for (Map<String, Object> d : st.datoms.values()) {
            if ("name".equals(d.get("a"))) continue;
            String key = st.factKey("culture", d);
            if (st.denied(key) && !byKey.containsKey(key)) byKey.put(key, d);
        }
        List<Map.Entry<String, Map<String, Object>>> es = new ArrayList<>(byKey.entrySet());
        Collections.sort(es, (x, y) -> st.denials.get(y.getKey()).compareTo(st.denials.get(x.getKey())));
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> x : es) {
            Map<String, Object> d = x.getValue();
            String e = st.find((String) d.get("e"));
            out.add(Tx.m("key", x.getKey(), "entity", names.containsKey(e) ? names.get(e) : e,
                    "a", d.get("a"), "v", display(st, names, d.get("v")), "quote", quoteOf(d)));
        }
        return out;
    }

    private static String display(State st, Map<String, String> names, Object v) {
        if (v instanceof Map && ((Map<?, ?>) v).containsKey("ref")) {
            String r = st.find((String) ((Map<?, ?>) v).get("ref"));
            return names.containsKey(r) ? names.get(r) : r;
        }
        return String.valueOf(v);
    }

    /** The log, newest first. */
    public synchronized List<Object> log(int offset, int limit) {
        List<Map<String, Object>> all = store.all();
        List<Object> out = new ArrayList<>();
        for (int i = all.size() - 1 - offset; i >= 0 && out.size() < limit; i--) {
            Map<String, Object> t = all.get(i);
            Map<String, Object> h = Tx.header(t);
            out.add(Tx.m("n", (long) i + 1, "id", Tx.id(t), "kind", Tx.kind(t), "portal", Tx.portal(t),
                    "tier", Tx.tier(t), "wall", Tx.wall(t), "directive", h.get("directive")));
        }
        return out;
    }

    public synchronized String tx(String id) {
        Map<String, Object> t = store.get(id);
        return t == null ? "null" : Json.canon(t);
    }

    /** Re-read the whole log from disk into a fresh store and replay it. */
    public synchronized Map<String, Object> verify() {
        return request("verify the log", false, this::doVerify);
    }

    private Map<String, Object> doVerify() {
        try {
            Store fresh = load(backend);
            String h = State.replay(fresh.valid()).stateHash();
            note("verify", "Re-read " + plural(fresh.size(), "transaction", "transactions") + " from disk · every hash, signature and link checks out",
                    Tx.m("ok", true, "count", (long) fresh.size()));
            return Tx.m("ok", true, "count", (long) fresh.size(), "state", h.substring(0, 12),
                    "matches", h.equals(state().stateHash()), "forks", (long) fresh.forkProofs().size());
        } catch (RuntimeException e) {
            note("verify", "Verification failed: " + e.getMessage(), Tx.m("ok", false));
            return Tx.m("ok", false, "error", String.valueOf(e.getMessage()));
        }
    }

    /** For tests: the raw store. */
    Store store() { return store; }
}
