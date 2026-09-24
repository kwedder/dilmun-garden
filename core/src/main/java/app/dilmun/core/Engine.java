package app.dilmun.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
    private final ArrayDeque<Map<String, Object>> trace = new ArrayDeque<>();
    private long traceSeq = 0;
    private Runnable traceListener;

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
     * What the engine just did, step by step, for the live map. Kept in memory
     * only (the log is the record; this is a view of work as it happens), and
     * capped, so a screen that never reads it costs nothing but a little memory.
     */
    public synchronized List<Object> trace(long since) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> ev : trace) if (((Number) ev.get("seq")).longValue() > since) out.add(ev);
        return out;
    }

    /** Called (on the engine's thread) after each new trace event. */
    public synchronized void onTrace(Runnable listener) { traceListener = listener; }

    private void note(String step, String text, Map<String, Object> data) {
        Map<String, Object> ev = new TreeMap<>(data);
        ev.put("seq", ++traceSeq);
        ev.put("t", clock.now());
        ev.put("step", step);
        ev.put("text", text);
        trace.addLast(ev);
        while (trace.size() > Policy.TRACE_KEEP) trace.removeFirst();
        if (traceListener != null) traceListener.run();
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
            note("refuse", "Refused: " + e.getMessage(), Tx.m("what", what, "reason", e.getMessage()));
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

    /** Deploy the agent on one directive, check what it proposes, and commit one result. */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> run(String did, Sources src) {
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
        Map<String, Object> proposal = agent.propose(d, text);                  // the only agent call
        note("propose", "Agent proposed " + plural(((List<?>) proposal.get("facts")).size(), "fact", "facts"),
                Tx.m("facts", (long) ((List<?>) proposal.get("facts")).size()));
        String skill = (String) d.get("skill");
        long version = ((Number) d.get("skill_version")).longValue();
        List<String> allowed = st.allowedTools(skill, version);
        List<Object> tools = (List<Object>) proposal.get("tools");
        long budget = ((Number) d.get("budget")).longValue();
        String nonce = Crypto.H(Arrays.asList(portal, did));

        List<Object> datoms = new ArrayList<>(), rejected = new ArrayList<>();
        List<Object> facts = (List<Object>) proposal.get("facts");
        String badTool = null;
        for (Object t : tools) if (!allowed.contains(t)) { badTool = (String) t; break; }
        long accepted = 0;
        for (int i = 0; i < facts.size(); i++) {
            Map<String, Object> f = (Map<String, Object>) facts.get(i);
            String reason = null;
            Map<String, Object> q = (Map<String, Object>) f.get("quote");
            int qs = ((Number) q.get("start")).intValue(), qe = ((Number) q.get("end")).intValue();
            String a = (String) f.get("a");
            if (badTool != null) reason = "tool not allowed by the skill: " + badTool;
            else if (qs < 0 || qe > text.length() || qs > qe || !text.substring(qs, qe).equals(q.get("text")))
                reason = "quote does not match the source";
            else if (!st.schema.containsKey(a) || "name".equals(a)) reason = "attribute not in the schema: " + a;
            else if (accepted >= budget) reason = "over budget";
            if (reason != null) {
                rejected.add(Tx.m("i", (long) i, "a", a, "reason", reason));
                continue;
            }
            List<Object> ident = (List<Object>) f.get("ident");
            String e = State.identId((String) ident.get(0), ident.get(1));
            Object v = f.get("v");
            if (Boolean.TRUE.equals(st.schema.get(a).get("ref"))) v = Tx.m("ref", State.identId("name", v));
            long vf = nextHlc()[0];
            datoms.add(datom("d:" + Crypto.H(Arrays.asList(nonce, (long) i)).substring(0, 24), e, a, v,
                    ((Number) f.get("nu")).longValue(), vf, q));
            datoms.add(datom("d:" + Crypto.H(Arrays.asList(nonce, (long) i, "name")).substring(0, 24), e, "name",
                    ident.get(1), 1000L, vf, q));
            if (Boolean.TRUE.equals(st.schema.get(a).get("ref"))) {        // the value is an entity too
                datoms.add(datom("d:" + Crypto.H(Arrays.asList(nonce, (long) i, "vname")).substring(0, 24),
                        State.identId("name", f.get("v")), "name", f.get("v"), 1000L, vf, q));
            }
            accepted++;
        }
        note("check", "Checked quotes, schema, tools, budget: " + accepted + " kept · " + rejected.size() + " refused",
                Tx.m("accepted", accepted, "rejected", (long) rejected.size()));
        Map<String, Object> tx = commit(portalKey, portal, "assert", "episode:" + did,
                Tx.m("proposal", proposal, "datoms", datoms, "rejected", rejected),
                Tx.m("directive", did, "agent", agent.id(), "skill", skill, "skill_version", version,
                        "skill_current", st.skills.get(skill).lastKey(), "model_hash", agent.id(),
                        "source", source, "source_hash", d.get("source_hash")));
        return Tx.m("tx", Tx.id(tx), "accepted", accepted, "rejected", rejected);
    }

    private static Map<String, Object> datom(String id, String e, String a, Object v, long nu, long vf, Map<String, Object> q) {
        return Tx.m("id", id, "e", e, "a", a, "v", v, "ctx", null, "nu", nu, "vf", vf, "vt", null,
                "quote", q, "origin", null, "origin_tx", null, "support", 1L);
    }

    /** Issue a directive for one source and run it straight away. */
    public synchronized Map<String, Object> extract(String sourceId, Sources src) {
        return request("extract " + nameOf(state(), sourceId), false, () -> run(issue(sourceId, Policy.DEFAULT_BUDGET), src));
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
        Set<String> promoted = new HashSet<>();
        for (Map<String, Object> d : st.tier("culture")) promoted.add(st.factKey("culture", d));
        TreeMap<String, List<Map<String, Object>>> groups = groupEpisodes(st);
        long n = 0;
        for (Map.Entry<String, List<Map<String, Object>>> g : groups.entrySet()) {
            String key = g.getKey();
            if (promoted.contains(key)) continue;
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
            boolean approved = appr != null && (after == null || appr.compareTo(after) > 0);
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
        long waiting = held().size();
        if (waiting > 0) note("hold", "Gate held " + plural(waiting, "fact", "facts") + " for more sources or approval", Tx.m("held", waiting));
        return n;
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

    // ------------------------------------------------------------ steward

    public synchronized void approve(List<String> keys) {
        List<String> sorted = new ArrayList<>(keys);
        Collections.sort(sorted);
        request("approve " + plural(sorted.size(), "held fact", "held facts"), true,
                () -> commit(stewardKey, Tx.STEWARD, "approve", "system", Tx.m("keys", new ArrayList<Object>(sorted)), null));
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
                rows.put(key, Tx.m("entity", ent, "a", d.get("a"), "v", val, "support", sup, "nu", d.get("nu")));
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
            if (support >= Policy.GATE_K) continue;
            String e = st.find((String) d.get("e"));
            out.add(Tx.m("key", g.getKey(), "entity", names.containsKey(e) ? names.get(e) : e,
                    "a", d.get("a"), "v", display(st, names, d.get("v")), "support", support,
                    "approved", st.approvals.containsKey(g.getKey())));
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
