package app.dilmun.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Tests for the core, runnable with plain Java (no test framework needed):
 *   gradle :core:coreTest      or      java app.dilmun.core.CoreTest
 */
public final class CoreTest {

    // ------------------------------------------------------------ fixtures

    /** Keeps transactions as JSON strings, the way SQLite does. */
    static final class MemBackend implements Engine.LogBackend {
        final List<String> txs = new ArrayList<>(), proofs = new ArrayList<>();
        @Override public List<Map<String, Object>> loadTxs() { List<Map<String, Object>> o = new ArrayList<>(); for (String s : txs) o.add(Json.obj(s)); return o; }
        @Override public List<Map<String, Object>> loadProofs() { List<Map<String, Object>> o = new ArrayList<>(); for (String s : proofs) o.add(Json.obj(s)); return o; }
        @Override public void putTx(Map<String, Object> tx) { String s = Json.canon(tx); if (!txs.contains(s)) txs.add(s); }
        @Override public void putProof(Map<String, Object> p) { String s = Json.canon(p); if (!proofs.contains(s)) proofs.add(s); }
        MemBackend copy() { MemBackend m = new MemBackend(); m.txs.addAll(txs); m.proofs.addAll(proofs); return m; }
    }

    static final class MemSources implements Engine.Sources {
        final TreeMap<String, String> files = new TreeMap<>();
        @Override public List<Map<String, Object>> list() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map.Entry<String, String> e : files.entrySet())
                out.add(Tx.m("id", "src:" + e.getKey(), "name", e.getKey(), "hash", Crypto.textHash(e.getValue())));
            return out;
        }
        @Override public String read(String id) { return files.get(id.substring(4)); }
    }

    static final class FakeClock implements Engine.Clock {
        long t = 1_700_000_000_000L;
        @Override public long now() { return t; }
    }

    static final class FakeLlm implements Llm {
        final String reply;
        int calls = 0;
        String lastSystem = "";
        FakeLlm(String reply) { this.reply = reply; }
        @Override public String id() { return "model:fake:0123456789ab"; }
        @Override public String generate(List<String[]> m, int max, float temp, boolean think, Llama.Sink sink) {
            calls++;
            lastSystem = m.get(0)[1];
            if (sink != null) sink.onPiece(reply.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return reply;
        }
        @Override public void stop() {}
        @Override public Map<String, Object> stats() { return Tx.m(); }
        @Override public int contextTokens() { return 4096; }
    }

    static final String S1 = "Notes on salicylates.\n- aspirin | treats | fever\n- aspirin | is_a | NSAID\nwillow bark | source_of | salicin\n";
    static final String S2 = "Handbook.\naspirin | treats | fever\nibuprofen | is_a | NSAID\naspirin | date | 1897\n";

    static int passed = 0, failed = 0;

    static void check(boolean ok, String what) {
        if (ok) { passed++; System.out.println("  ok    " + what); }
        else { failed++; System.out.println("  FAIL  " + what); }
    }

    static void rejects(Runnable r, String contains, String what) {
        try { r.run(); check(false, what + " (nothing was refused)"); }
        catch (Store.Rejected e) { check(e.getMessage().contains(contains), what + " -> " + e.getMessage()); }
    }

    static boolean hasFact(Engine e, String ent, String a, String v) {
        for (Object o : e.memory("")) {
            Map<?, ?> r = (Map<?, ?>) o;
            if (ent.equals(r.get("entity")) && a.equals(r.get("a")) && v.equals(r.get("v"))) return true;
        }
        return false;
    }

    static final class World {
        final MemBackend db = new MemBackend();
        final Crypto.SoftSigner portal = new Crypto.SoftSigner(), steward = new Crypto.SoftSigner();
        final FakeClock clock = new FakeClock();
        final MemSources src = new MemSources();
        Engine e;
        World() {
            src.files.put("notes.md", S1);
            src.files.put("handbook.md", S2);
            e = Engine.open(db, portal, steward, clock, new Agent.PatternAgent());
        }
        Engine reopen() { e = Engine.open(db, portal, steward, clock, new Agent.PatternAgent()); return e; }
    }

    // ------------------------------------------------------------ tests

    public static void main(String[] args) {
        System.out.println("Dilmun core");

        section("genesis and the workspace map");
        World w = new World();
        check(w.e.store().genesisId() != null, "a new log starts with a steward-signed genesis");
        Map<String, Object> sc = w.e.scan(w.src);
        check(((Number) sc.get("changed")).longValue() == 2, "scan maps both sources");
        int before = w.db.txs.size();
        w.e.scan(w.src);
        check(w.db.txs.size() == before, "rescanning unchanged sources commits nothing");

        section("directives, checks and the gate");
        Map<String, Object> r1 = w.e.extract("src:notes.md", w.src);
        check(((Number) r1.get("accepted")).longValue() == 2, "two facts from notes.md pass the checks");
        check(r1.toString().contains("attribute not in the schema: source_of"), "an attribute outside the schema is refused, with the reason recorded");
        w.clock.t += 1000;
        w.e.extract("src:handbook.md", w.src);
        check(w.e.memory("").isEmpty(), "nothing is in culture before the gate runs");
        long promoted = w.e.gate();
        check(promoted > 0, "the gate promotes (" + promoted + " copies)");
        check(hasFact(w.e, "aspirin", "treats", "fever"), "aspirin treats fever: two sources, promoted");
        check(!hasFact(w.e, "aspirin", "is_a", "NSAID"), "aspirin is_a NSAID: one source, held");
        List<Object> held = w.e.held();
        check(held.size() >= 2, "held items are listed (" + held.size() + ")");
        String key = null;
        for (Object o : held) { Map<?, ?> h = (Map<?, ?>) o; if ("aspirin".equals(h.get("entity")) && "is_a".equals(h.get("a"))) key = (String) h.get("key"); }
        w.e.approve(Collections.singletonList(key));
        w.e.gate();
        check(hasFact(w.e, "aspirin", "is_a", "NSAID"), "the steward's approval lets a held fact through");
        check(w.e.gate() == 0, "running the gate again changes nothing");

        section("pause");
        w.e.pause();
        rejects(() -> w.e.extract("src:notes.md", w.src), "paused", "no directive while paused");
        rejects(w.e::gate, "paused", "no gate while paused");
        w.e.resume();
        w.e.extract("src:notes.md", w.src);
        check(true, "resuming allows work again");

        section("the workspace map steers every run");
        String did = w.e.issue("src:handbook.md", 50);
        w.src.files.put("handbook.md", S2 + "fever | part_of | inflammation\n");
        rejects(() -> w.e.run(did, w.src), "source changed", "a run on a changed file is refused");
        rejects(() -> w.e.issue("src:unknown.md", 50), "workspace map", "an unmapped source can't be named");
        w.e.scan(w.src);
        check(((Number) w.e.extract("src:handbook.md", w.src).get("accepted")).longValue() == 4, "after a rescan, a new directive reads the new text");

        section("budgets and expiry");
        String small = w.e.issue("src:handbook.md", 1);
        Map<String, Object> r2 = w.e.run(small, w.src);
        check(((Number) r2.get("accepted")).longValue() == 1 && r2.toString().contains("over budget"), "a result stops at its budget");
        String late = w.e.issue("src:notes.md", 50);
        w.clock.t += Policy.TTL_MS + 1;
        rejects(() -> w.e.run(late, w.src), "expired", "no run after a directive expires");
        check(w.e.reconcile() >= 1, "startup reconciliation expires stale directives");
        check(w.e.directives().isEmpty(), "no open directives remain");

        section("the registry steers every run");
        w.e.publishSkill("ingest", 2, Collections.singletonList("read_index"));
        Map<String, Object> r3 = w.e.extract("src:notes.md", w.src);
        check(((Number) r3.get("accepted")).longValue() == 0 && r3.toString().contains("tool not allowed"), "a tightened skill applies at once");
        rejects(() -> w.e.publishSkill("ingest", 2, Collections.singletonList("shell")), "never changes", "a published skill version can't be changed");
        w.e.publishSkill("ingest", 3, Collections.singletonList("read_source"));
        check(((Number) w.e.extract("src:notes.md", w.src).get("accepted")).longValue() == 2, "a new version restores it");

        section("the store's own rules");
        Store s = w.e.store();
        Crypto.SoftSigner stranger = new Crypto.SoftSigner();
        rejects(() -> s.append(Tx.make(stranger, w.e.portal, s.tip(w.e.portal), new long[]{w.clock.t + 5, 0}, "map", "system", Tx.m("places", Tx.m()), null)),
                "unknown signer", "an unknown key (an agent's, say) can't commit");
        rejects(() -> s.append(Tx.make(w.portal, Tx.STEWARD, s.tip(Tx.STEWARD), new long[]{w.clock.t + 5, 0}, "pause", "system", Tx.m(), null)),
                "only the steward", "a portal can't pause");
        rejects(() -> s.append(Tx.make(w.portal, w.e.portal, s.tip(w.e.portal), new long[]{w.clock.t + 5, 0}, "map", "culture", Tx.m("places", Tx.m()), null)),
                "promotion gate", "nothing but the gate writes to culture");
        Map<String, Object> forged = Tx.make(w.portal, w.e.portal, s.tip(w.e.portal), new long[]{w.clock.t + 5, 0}, "map", "system", Tx.m("places", Tx.m("x", "1")), null);
        Tx.payload(forged).put("places", Tx.m("x", "2"));
        rejects(() -> s.append(forged), "payload does not match", "a changed payload is refused");

        section("restart and replay");
        String hash = w.e.state().stateHash();
        Engine again = w.reopen();
        check(again.state().stateHash().equals(hash), "restarting from the log gives the same state");
        again.scan(w.src);
        again.extract("src:notes.md", w.src);
        check(Boolean.TRUE.equals(again.verify().get("matches")), "verify re-reads the whole log and matches");
        List<Map<String, Object>> txs = again.store().valid();
        String want = State.replay(txs).stateHash();
        boolean same = true;
        for (int i = 0; i < 20; i++) { Collections.shuffle(txs, new Random(i)); same &= State.replay(txs).stateHash().equals(want); }
        check(same, "replay in any order gives the same state");
        MemBackend tampered = w.db.copy();
        int idx = -1;
        for (int i = 0; i < tampered.txs.size(); i++) if (tampered.txs.get(i).contains("\"kind\":\"assert\"")) { idx = i; break; }
        tampered.txs.set(idx, tampered.txs.get(idx).replace("fever", "fevers"));
        rejects(() -> Engine.open(tampered, w.portal, w.steward, w.clock, new Agent.PatternAgent()), "does not match", "a log edited on disk fails to open");

        section("forks");
        World f = new World();
        f.e.scan(f.src);
        MemBackend backup = f.db.copy();
        f.e.extract("src:notes.md", f.src);                                  // branch 1
        f.clock.t += 10;
        Engine restored = Engine.open(backup, f.portal, f.steward, f.clock, new Agent.PatternAgent());
        restored.extract("src:handbook.md", f.src);                          // branch 2, same parent
        Store mine = f.e.store();
        int forkRejections = 0;
        for (Map<String, Object> t : Tx.sorted(restored.store().all(), Tx.LOAD)) {
            try { mine.append(t); } catch (Store.Rejected x) { if (x.getMessage().startsWith("fork") || mine.frozen(f.e.portal)) forkRejections++; }
        }
        check(mine.frozen(f.e.portal), "the fork is proven where the branches meet");
        check(forkRejections >= 1, "the second branch is refused");
        boolean onlyPrefix = true;
        for (Map<String, Object> t : mine.valid()) if (f.e.portal.equals(Tx.portal(t)) && !"map".equals(Tx.kind(t))) onlyPrefix = false;
        check(onlyPrefix, "both branches stop counting; the prefix still does");
        rejects(() -> f.e.scan(f.src), "frozen", "a frozen portal refuses to act");

        section("the model as an agent");
        World g = new World();
        g.src.files.put("pharma.md", "Aspirin, an NSAID, is used to treat fever.\nIt was first sold in 1899.\n");
        g.e.scan(g.src);
        FakeLlm fake = new FakeLlm(
                "aspirin | is_a | NSAID | Aspirin, an NSAID\n"
                + "Aspirin | treats | fever | used to treat fever\n"
                + "aspirin | treats | headache | cures every headache\n"
                + "aspirin | invented_by | Hoffmann | first sold in 1899\n"
                + "not a fact line\n");
        ModelAgent ma = new ModelAgent(fake, Policy.SCHEMA_ORDER, null);
        Map<String, Object> mr = g.e.extract("src:pharma.md", g.src, ma);
        check(((Number) mr.get("accepted")).longValue() == 2, "facts whose quotes are in the file are kept (2)");
        check(mr.toString().contains("quote does not match the source"), "a quote the model invented is refused");
        check(mr.toString().contains("attribute not in the schema: invented_by"), "an attribute outside the schema is refused");
        Map<String, Object> mtx = g.e.store().get((String) mr.get("tx"));
        check("model:fake:0123456789ab".equals(Tx.header(mtx).get("model_hash")), "the result names the exact model file");
        check(Json.canon(Tx.payload(mtx).get("proposal")).contains("cures every headache"), "the model's raw output is recorded in the episode");
        check(fake.calls == 1 && fake.lastSystem.contains("is_a, part_of"), "one call per passage, with the schema in the prompt");
        List<int[]> ps = ModelAgent.passages(S1 + S1 + S1 + S1 + S1 + S1 + S1 + S1 + S1 + S1 + S1 + S1 + S1 + S1 + S1 + S1 + S1 + S1 + S1 + S1 + S1 + S1 + S1 + S1 + S1, 400);
        boolean tiles = ps.get(0)[0] == 0;
        for (int i = 1; i < ps.size(); i++) tiles &= ps.get(i)[0] == ps.get(i - 1)[1] && ps.get(i)[1] - ps.get(i)[0] <= 400;
        check(tiles && ps.size() > 3, "long text is split into passages that cover it exactly (" + ps.size() + ")");
        check(State.identId("name", "Aspirin").equals(State.identId("name", "  aspirin ")), "names are one entity whatever their case or spacing");

        section("ask");
        g.src.files.put("hand.md", "Handbook: aspirin, an NSAID, relieves pain.\n- aspirin | is_a | NSAID\n");
        g.e.scan(g.src);
        g.e.extract("src:hand.md", g.src);
        g.e.gate();
        List<Object> rec = g.e.recall("Is aspirin an NSAID?", 5);
        check(!rec.isEmpty() && rec.toString().contains("NSAID"), "recall finds the settled fact for a question");
        check(g.e.recall("What is the capital of France?", 5).isEmpty(), "recall finds nothing for an unrelated question");
        List<String[]> am = Ask.messages(new ArrayList<String[]>(), "Is aspirin an NSAID?", rec, true);
        check(am.get(0)[1].contains("[1] aspirin is a NSAID") && am.get(am.size() - 1)[1].equals("Is aspirin an NSAID?"), "the prompt numbers the facts for citation");
        int before2 = g.e.store().size();
        g.e.recall("aspirin", 5);
        check(g.e.store().size() == before2, "asking writes nothing to the log");

        section("canonical JSON");
        Map<String, Object> m = Tx.m("b", 1L, "a", Arrays.asList("x", "é\n\"q\""), "c", null, "d", true);
        check(Json.canon(m).equals("{\"a\":[\"x\",\"é\\n\\\"q\\\"\"],\"b\":1,\"c\":null,\"d\":true}"), "keys sorted, escapes stable");
        check(Json.canon(Json.parse(Json.canon(m))).equals(Json.canon(m)), "parse and print round-trip");
        boolean refused = false;
        try { Json.canon(Tx.m("x", 0.5)); } catch (IllegalArgumentException x) { refused = true; }
        check(refused, "fractions are refused, so hashes never depend on float printing");

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void section(String name) { System.out.println(); System.out.println(name); }
}
