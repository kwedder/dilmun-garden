package app.dilmun.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
        String lastSystem = "", lastUser = "", lastGrammar = null;
        FakeLlm(String reply) { this.reply = reply; }
        @Override public String generate(List<String[]> m, int max, float temp, boolean think, String grammar, Llama.Sink sink) {
            lastGrammar = grammar;
            return generate(m, max, temp, think, sink);
        }
        @Override public String id() { return "model:fake:0123456789ab"; }
        @Override public String generate(List<String[]> m, int max, float temp, boolean think, Llama.Sink sink) {
            calls++;
            lastSystem = m.get(0)[1];
            lastUser = m.get(m.size() - 1)[1];
            if (sink != null) sink.onPiece(reply.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return reply;
        }
        @Override public void stop() {}
        @Override public Map<String, Object> stats() { return Tx.m(); }
        @Override public int contextTokens() { return 4096; }
    }

    static final String S1 = "Notes on salicylates.\n- aspirin | treats | fever\n- aspirin | is_a | NSAID\nwillow bark | source_of | salicin\n";
    static final String S2 = "Handbook.\naspirin | treats | fever\nibuprofen | is_a | NSAID\naspirin | date | 1897\n";

    static String show(List<String[]> facts) {
        StringBuilder sb = new StringBuilder();
        for (String[] f : facts) sb.append(Json.canon(Arrays.asList((Object[]) f)));
        return sb.toString();
    }

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

        section("grounding");
        check(Grounding.check("anthropology", "is_a", "vast", "Anthropology is a vast field of study.") != null
                && Grounding.check("anthropology", "is_a", "vast field of study", "Anthropology is a vast field of study.") == null,
                "a value cut short is refused (\"vast\"), the whole phrase is kept");
        check(Grounding.check("biological", "is_a", "human beings", "Biological anthropology is the study of human beings.") != null
                && Grounding.check("biological anthropology", "is_a", "study of human beings", "Biological anthropology is the study of human beings.") == null,
                "a modifier taken for the thing is refused (\"biological\")");
        check(Grounding.check("snakes", "is_a", "primates", "Snakes are reptiles, unlike primates.") != null
                && Grounding.check("snake", "is_a", "reptile", "Snakes are reptiles, unlike primates.") == null,
                "is_a needs the quote to say it, and plurals match");
        check(String.valueOf(Grounding.check("humanity", "defined_as", "humanity", "Humanity is defined as humanity.")).contains("repeats"),
                "a thing defined as itself is refused");
        check(Grounding.check("ibuprofen", "is_a", "NSAID", "NSAIDs such as ibuprofen reduce pain.") == null
                && Grounding.check("ibuprofen", "is_a", "NSAID", "Ibuprofen, an NSAID, is used to treat pain.") == null,
                "\"V such as E\" and \"E, a V\" count as is_a");
        check(Grounding.check("smoking", "causes", "lung", "Smoking causes lung cancer.") != null
                && Grounding.check("smoking", "causes", "lung cancer", "Smoking causes lung cancer in adults.") == null,
                "other attributes: the value must be the whole phrase too");
        check(Grounding.check("aspirin", "treats", "fever", "used to treat fever", "Aspirin, an NSAID, is used to treat fever.", "") == null
                && Grounding.check("aspirin", "date", "1899", "first sold in 1899", "It was first sold in 1899.", "Aspirin is an NSAID.") == null
                && Grounding.check("morphine", "date", "1899", "first sold in 1899", "It was first sold in 1899.", "Aspirin is an NSAID.") != null,
                "the entity may be elsewhere in the sentence, or before an \"It\"");
        World gw = new World();
        gw.src.files.put("anth.md", "Anthropology is a vast field of study. Biological anthropology is the study of human beings.\n");
        gw.e.scan(gw.src);
        Map<String, Object> gr = gw.e.extract("src:anth.md", gw.src, new ModelAgent(new FakeLlm(
                "anthropology | is_a | vast | Anthropology is a vast\n"
                + "biological | is_a | human beings | Biological anthropology is the study of human beings\n"
                + "biological anthropology | is_a | study of human beings | Biological anthropology is the study of human beings\n"),
                Policy.SCHEMA_ORDER, null));
        check(gr.toString().contains("does not say"), "the arbiters refuse the model's inaccurate facts, with the reason");
        String kept = gw.e.held().toString();
        check(kept.contains("biological_anthropology") && kept.contains("study_of_human_beings"), "and write it as concepts: " + kept);
        check(Grounding.check("anthropology", "is_a", "vast", "anthropology is vast") != null
                && Grounding.check("anthropology", "is_a", "field of study", "Anthropology is a vast field of study.") == null,
                "\"anthropology is vast\" is an adjective, not a kind; \"is a vast field of study\" names a field of study");
        check(Grounding.check("fieldwork", "is_a", "practice", "This practice is called fieldwork.") == null
                && Grounding.check("orientalism", "is_a", "style", "a style known as orientalism") == null
                && Grounding.check("enculturation", "defined_as", "process of acquiring our particular culture",
                        "Anthropologists call this process of acquiring our particular culture enculturation.") == null
                && Grounding.check("Carel van Schaik", "is_a", "primatologist", "The Dutch primatologist Carel van Schaik spent six years") == null
                && Grounding.check("anthropology", "is_a", "biological", "Biological anthropology focuses on") != null,
                "names given backwards count: \"is called E\", \"known as E\", \"call this V E\", \"the primatologist Carel\"");
        check(Grounding.concept("a vast field of study").equals("field_of_study") && Grounding.concept("Primates").equals("primate")
                && Grounding.concept("Carel van Schaik").equals("Carel_van_Schaik") && Grounding.concept("West African country").equals("West_African_country")
                && Grounding.concept("NSAIDs").equals("NSAID") && Grounding.concept("field_of_study").equals("field_of_study")
                && Grounding.concept("species").equals("species"),
                "a concept is the same key however a source words it");

        section("one pass, numbered sentences");
        String para = "# The Study of Humanity\nAnthropology is a vast field of study. Biological anthropology is the study of human beings. "
                + "Dr. Owsley works in Peru and the U.S. today. What do you think?\n";
        List<int[]> ss = ModelAgent.sentences(para, 0, para.length());
        List<String> sts = new ArrayList<>();
        for (int[] x : ss) sts.add(para.substring(x[0], x[1]));
        check(sts.size() == 5 && sts.get(1).equals("Anthropology is a vast field of study.") && sts.get(3).startsWith("Dr. Owsley works in Peru and the U.S. today"),
                "the passage is split into sentences, abbreviations kept whole: " + sts);
        World nw = new World();
        nw.src.files.put("anth.md", para);
        nw.e.scan(nw.src);
        FakeLlm nf = new FakeLlm("2 | anthropology | is_a | vast\n"            // one off: sentence 1 says it
                + "2 | biological | is_a | study_of_human_beings\n"
                + "3 | Owsley | located_in | Peru\n"
                + "9 | anthropology | is_a | science\n"
                + "4 | you | is_a | thinker\n");
        Map<String, Object> nr = nw.e.extract("src:anth.md", nw.src, new ModelAgent(nf, Policy.SCHEMA_ORDER, null));
        String nh = nw.e.held().toString();
        check(nf.lastUser.contains("[1] Anthropology is a vast field of study.") && !nf.lastUser.contains("Study of Humanity")
                && nf.lastSystem.contains("sentence number | letter | relation | letter"),
                "the model gets numbered sentences, headings left out, and answers with a sentence number instead of a quote");
        check(nh.contains("v=field_of_study") && nh.contains("entity=biological_anthropology") && nh.contains("v=Peru"),
                "facts cut short or numbered one off are finished and re-pointed from the sentences, and kept only if they then hold up: " + nh);
        List<String[]> hv = Grounding.harvest("Biological anthropology is the study of human beings, and the Wauja, an indigenous group in Brazil, "
                + "call this practice fieldwork.");
        List<String[]> hv2 = Grounding.harvest("This practice is called fieldwork.");
        List<String[]> hv3 = Grounding.harvest("Other social disciplines, such as political science, religious studies, and economics, differ.");
        List<String[]> hv4 = Grounding.harvest("The Dutch primatologist Carel van Schaik spent six years in Sumatra.");
        check(show(hv).contains("[\"Biological anthropology\",\"is_a\",\"study of human beings\"]")
                && show(hv).contains("[\"Wauja\",\"is_a\",\"indigenous group\"]")
                && show(hv2).contains("[\"fieldwork\",\"is_a\",\"practice\"]")
                && show(hv3).contains("[\"political science\",\"is_a\",\"social disciplines\"]") && show(hv3).contains("[\"economics\",\"is_a\",\"social disciplines\"]")
                && show(hv4).contains("[\"Carel van Schaik\",\"is_a\",\"Dutch primatologist\"]"),
                "the rules read \"E is a V\", \"E, a V\", \"V is called E\", \"V such as E\" and titles by themselves: "
                        + show(hv) + show(hv2) + show(hv3) + show(hv4));
        check(nr.toString().contains("quote does not match the source") && nr.toString().contains("not a thing"),
                "a sentence number that isn't there, and a pronoun for an entity, are still refused");
        ModelAgent am2 = new ModelAgent(nf, Policy.SCHEMA_ORDER, null);
        check(am2.attribute("defines_as").equals("defined_as") && am2.attribute("location_in").equals("located_in")
                && am2.attribute("is_a").equals("is_a") && am2.attribute("attribute").equals("attribute"),
                "a misspelt attribute is read as the one schema attribute it's close to; anything else stays, and is refused");
        check(!am2.systemPrompt().toLowerCase(Locale.ROOT).contains("anthropolog"), "the prompt names no example concept a model could copy into its facts");
        String junk = "Anthropologists are committed to describing cultures. The brain, the heart, the liver, and the skeleton work together. "
                + "There are four varnas known across India: Brahmins. A person from the United States or Europe is locally referred to as an obruni. "
                + "Artifacts are objects made by human beings, such as tools or pottery. Researching this argument is a vast endeavor.";
        List<String> junkKept = new ArrayList<>();
        for (int[] x : ModelAgent.sentences(junk, 0, junk.length())) {
            String sj = junk.substring(x[0], x[1]);
            for (String[] h : Grounding.harvest(sj)) if (Grounding.check(h[0], h[1], h[2], sj, sj, "") == null) junkKept.add(h[0] + "|" + h[2]);
        }
        check(Grounding.check("ethnography", "is_a", "Argonauts of the Western Pacific", "His ethnography, Argonauts of the Western Pacific (1922), describes how") != null
                && Grounding.check("clothing", "is_a", "cultures", "As with clothing, different cultures come up with solutions.") != null
                && Grounding.check("ibuprofen", "is_a", "NSAID", "Ibuprofen, an NSAID, is used to treat pain.") == null,
                "a bare comma isn't \"is a\"; \"E, an V\" still is");
        check(junkKept.isEmpty(), "the rules don't read adjectives, lists, adverbs, phrase tails or \"such as\" on the wrong noun as kinds: " + junkKept);
        List<String[]> hv5 = Grounding.harvest("Nineteenth-century explorers such as Henry M. Stanley described Africa, and from Kinshasa, the capital of the Democratic Republic of the Congo, we drove.");
        check(show(hv5).contains("[\"Henry M. Stanley\",\"is_a\",\"Nineteenth-century explorers\"]") && show(hv5).contains("capital of the Democratic Republic of the Congo")
                && Grounding.concept("Henry M. Stanley").equals("Henry_M_Stanley"),
                "initials and long names stay whole: " + show(hv5));
        section("a briefing, a menu and a grammar");
        World mw = new World();
        mw.src.files.put("anth.md", "Anthropology is a vast field of study. Smoking causes lung cancer in adults. "
                + "Susan Bayly describes caste in India.\n");
        mw.e.scan(mw.src);
        FakeLlm mf = new FakeLlm("2 | a | causes | b\n"          // smoking causes lung cancer: on the menu, and the sentence says it
                + "3 | a | located_in | c\n"                        // Susan Bayly located_in India: the sentence doesn't say so
                + "2 | a | causes | a\n"                            // the same thing twice
                + "2 | a | causes | h\n");                          // off the menu
        ModelAgent mag = new ModelAgent(mf, Policy.SCHEMA_ORDER, null).briefed(Arrays.asList("12 facts named a value the sentence doesn't hold"));
        Map<String, Object> mr2 = mw.e.extract("src:anth.md", mw.src, mag);
        String mh = mw.e.held().toString();
        check(mf.lastUser.contains("[2] Smoking causes lung cancer in adults.") && mf.lastUser.contains("a) Smoking  b) lung cancer"),
                "each sentence comes with a lettered menu of the things it names: " + mf.lastUser);
        check(mf.lastUser.contains("1 | a | is_a | b") && mf.lastUser.contains("don't repeat"),
                "the rules' verified facts from the passage are shown as examples of the form");
        check(mf.lastGrammar != null && mf.lastGrammar.contains("\"is_a\" | \"part_of\"") && mf.lastGrammar.contains("letter ::= [a-h]")
                && !mf.lastGrammar.contains("\"name\""),
                "the output grammar is built from the schema: only its relations, only menu letters");
        check(mf.lastSystem.contains("The arbiters' notes on your last reports") && mf.lastSystem.contains("12 facts named a value"),
                "the arbiters' notes on the model's last results are in its briefing");
        check(mh.contains("entity=smoking") && mh.contains("v=lung_cancer") && !mh.contains("v=India")
                && Json.canon(Tx.payload(mw.e.store().get((String) mr2.get("tx")))).contains("\"menus\""),
                "a menu answer becomes the fact it names; the arbiters still refuse what the sentence doesn't say; the menus are kept: " + mh);

        long enlistBefore = mw.e.store().size();
        Map<String, Object> en = mw.e.present(mf.id());
        long enlistAfter = mw.e.store().size();
        mw.e.present(mf.id());
        List<String> notesNow = mw.e.notes(mf.id());
        check(enlistAfter == enlistBefore + 1 && mw.e.store().size() == enlistAfter && Json.canon(en).contains("\"grammar\"")
                && mw.e.state().enlisted.containsKey(mf.id()),
                "a loaded model goes before the arbiters once: enlisted in the log under its briefings' hashes");
        check(!notesNow.isEmpty() && notesNow.get(0).matches("1 of your last \\d+ facts were kept\\.") && Json.canon(notesNow).contains("were refused"),
                "the arbiters' notes count the model's own facts, not the rules': " + notesNow);
        List<Object> mrec = mw.e.recall("What causes lung cancer?", 5);
        check(!mrec.isEmpty() && Ask.system(mrec).startsWith("You are Dilmun") && Ask.system(mrec).contains("How the store is laid out")
                && Ask.system(mrec).contains("field_of_study is \"field of study\"") && Ask.system(mrec).contains("smoking causes lung_cancer"),
                "a model answering from memory first reads how the store is laid out");

        ModelAgent.LineWatch lw = new ModelAgent.LineWatch();
        check(lw.more("1 | a | is_a | b\n2 | c | is_a | d\n") && lw.more("1 | a | is_a | b\n") && !lw.more("1 | a | is_a | b\n"),
                "a generation that repeats the same line three times is stopped");

        section("deny and edit at the gate");
        World dw = new World();
        dw.src.files.put("a.md", "- snakes | is_a | primates\n- snakes | is_a | reptiles\n- anthropology | is_a | vast\n");
        dw.src.files.put("b.md", "- snakes | is_a | primates\n");
        dw.e.scan(dw.src);
        dw.e.extract("src:a.md", dw.src);
        String wrongKey = null, vastKey = null, reptKey = null;
        for (Object o : dw.e.held()) {
            Map<?, ?> h = (Map<?, ?>) o;
            if ("primates".equals(h.get("v"))) wrongKey = (String) h.get("key");
            if ("vast".equals(h.get("v"))) vastKey = (String) h.get("key");
            if ("reptiles".equals(h.get("v"))) reptKey = (String) h.get("key");
        }
        dw.e.deny(Collections.singletonList(wrongKey));
        dw.e.gate();
        check(!dw.e.held().toString().contains("primates") && dw.e.denied().toString().contains("primates"),
                "a denied fact leaves the held list and shows as denied");
        dw.e.extract("src:b.md", dw.src);
        dw.e.gate();
        check(!dw.e.memory("").toString().contains("primates"), "a denied fact is never promoted, even when a second source agrees");
        check(dw.e.recall("snakes primates", 5).toString().indexOf("primates") < 0, "and Ask never sees it");
        dw.e.approve(Collections.singletonList(wrongKey));
        dw.e.gate();
        check(dw.e.memory("").toString().contains("primates") && dw.e.denied().isEmpty(), "approving it later undoes the denial");
        dw.e.deny(Collections.singletonList(wrongKey));
        dw.e.gate();
        check(!dw.e.memory("").toString().contains("primates"), "denying a settled fact withdraws it from culture");
        dw.e.correct(vastKey, "anthropology", "is_a", "field of study");
        dw.e.gate();
        String mem = dw.e.memory("").toString();
        check(mem.contains("field of study") && mem.contains("edited=true") && !dw.e.held().toString().contains("vast"),
                "an edited fact goes to culture as written, and the original leaves the gate");
        boolean noop = false;
        try { dw.e.correct(reptKey, "snakes", "is_a", "reptiles"); } catch (Store.Rejected ex) { noop = true; }
        check(noop, "an edit that changes nothing is refused");
        check(State.replay(dw.e.store().valid()).stateHash().equals(dw.e.state().stateHash()), "denials and edits replay from the log to the same state");

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
        List<String[]> at = Ask.messages(new ArrayList<String[]>(), "Is aspirin an NSAID?", rec, true, true);
        String[] last = at.get(at.size() - 1);
        check("prefill".equals(last[0]) && last[1].contains("[1] aspirin is a NSAID") && last[1].endsWith("[1]")
                && at.get(at.size() - 2)[1].equals("Is aspirin an NSAID?"),
                "with think on, the model's reasoning starts from the facts, one by one");
        check(!"prefill".equals(am.get(am.size() - 1)[0])
                && !"prefill".equals(Ask.messages(new ArrayList<String[]>(), "q", rec, false, true).get(1)[0]),
                "no prefill without think, or without memory");
        check(Ask.reasoning("What is x?", new ArrayList<Object>()).contains("no facts"), "with no facts, the reasoning starts by admitting it");
        int before2 = g.e.store().size();
        long rmark = seqOf(g.e.trace(0));
        g.e.recall("aspirin", 5);
        check(g.e.store().size() == before2, "asking writes nothing to the log");
        check(stepsOf(g.e.trace(rmark)).equals(Collections.singletonList("recall")), "but the read is traced, so the map shows Ask using memory");
        World hw = new World();
        hw.src.files.put("ethno.md", "Ethnography is a research method.\n- ethnography | is_a | research method\n");
        hw.e.scan(hw.src);
        hw.e.extract("src:ethno.md", hw.src);
        hw.e.extract("src:notes.md", hw.src);
        hw.e.extract("src:handbook.md", hw.src);
        hw.e.gate();
        List<Object> one = hw.e.recall("What is ethnography?", 5);
        check(one.size() == 1 && "held".equals(((Map<?, ?>) one.get(0)).get("status")),
                "with one source, recall still finds the fact, marked as held at the gate");
        check(Ask.line(1, castMap(one.get(0))).contains("unconfirmed: 1 source, not yet through the gate")
                && Ask.system(one).contains("unconfirmed come from a single source"), "the prompt tells the model which facts are unconfirmed");
        List<Object> mixed = hw.e.recall("aspirin fever NSAID", 5);
        check(!mixed.isEmpty() && "settled".equals(((Map<?, ?>) mixed.get(0)).get("status")) && mixed.toString().contains("held"),
                "settled facts come first, unconfirmed ones after: " + mixed.size());
        hw.src.files.put("field.md", "Fieldwork. Ethnography is a research method based on participant observation.\n");
        hw.e.scan(hw.src);
        hw.e.extract("src:field.md", hw.src, new ModelAgent(new FakeLlm(
                "ethnography | is_a | research method | Ethnography is a research method based on participant observation\n"),
                Policy.SCHEMA_ORDER, null));
        List<Object> byQuote = hw.e.recall("What is participant observation?", 5);
        check(byQuote.size() == 1 && byQuote.toString().contains("participant observation"),
                "a question can match a fact through its quote, not just its words");

        section("the live trace");
        World lt = new World();
        lt.e.scan(lt.src);
        long mark = seqOf(lt.e.trace(0));
        final int[] nudges = {0};
        lt.e.onTrace(() -> nudges[0]++);
        lt.e.extract("src:notes.md", lt.src);
        List<Object> steps = lt.e.trace(mark);
        List<Object> main = stepsOf(steps);
        long factSteps = Collections.frequency(main, "fact");
        main.removeAll(Collections.singletonList("fact"));
        check(main.equals(Arrays.asList("request", "steer", "sign", "commit", "deploy", "read", "propose",
                "check", "sign", "commit", "answer")), "an extract is traced step by step, in order: " + main);
        check(factSteps == 3 && Json.canon(steps).contains("Refused willow bark · source_of · salicin · attribute not in the schema"),
                "each proposed fact is traced as kept or refused, with the reason (" + factSteps + ")");
        check(nudges[0] == steps.size(), "the listener hears every step as it happens");
        check(seqOf(lt.e.trace(seqOf(steps))) == 0, "reading from the last seq returns nothing new");
        mark = seqOf(steps);
        lt.e.pause();
        rejects(() -> lt.e.extract("src:notes.md", lt.src), "paused", "a paused extract is refused");
        List<Object> after = lt.e.trace(mark);
        check(stepsOf(after).get(0).equals("request") && stepsOf(after).get(after.size() - 1).equals("refuse"),
                "a refusal is traced with its reason: " + stepsOf(after));
        check(Json.canon(lt.e.trace(0)).length() > 0, "trace events are canonical JSON");
        int logSize = lt.e.store().size();
        for (int i = 0; i < 300; i++) lt.e.verify();
        check(lt.e.trace(0).size() == Policy.TRACE_KEEP, "the trace keeps only the newest " + Policy.TRACE_KEEP + " events");
        check(lt.e.store().size() == logSize, "tracing writes nothing to the log");
        boolean badStep = false;
        try { lt.e.noteWork("commit", "pretend", null); } catch (IllegalArgumentException x) { badStep = true; }
        check(badStep, "outside work can't pose as an engine step");

        section("the model, passage by passage");
        World pw = new World();
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 6; i++) longText.append("Aspirin, an NSAID, is used to treat fever. ").append("Filler sentence number ").append(i).append(" about nothing much at all, repeated to make the passage long enough to split. ".repeat(20)).append("\n\n");
        pw.src.files.put("long.md", longText.toString());
        pw.e.scan(pw.src);
        FakeLlm pf = new FakeLlm("aspirin | is_a | NSAID | Aspirin, an NSAID\naspirin | treats | headache | cures every headache\n");
        final List<int[]> seenPassages = new ArrayList<>();
        ModelAgent traced = new ModelAgent(pf, Policy.SCHEMA_ORDER, ModelAgent.traced(pw.e, "long.md", new ModelAgent.Progress() {
            @Override public void passage(int i, int n) { seenPassages.add(new int[]{i, n}); }
            @Override public void text(String piece) { }
        }));
        long pmark = seqOf(pw.e.trace(0));
        pw.e.extract("src:long.md", pw.src, traced);
        List<Object> pev = pw.e.trace(pmark);
        List<Object> pst = stepsOf(pev);
        int np = ModelAgent.passages(longText.toString(), ModelAgent.PASSAGE_CHARS).size();
        check(np > 1 && Collections.frequency(pst, "passage") == np && Collections.frequency(pst, "passage-done") == np,
                "every passage is traced as the model reads it (" + np + " passages)");
        check(pst.indexOf("passage") > pst.indexOf("read") && pst.lastIndexOf("passage-done") < pst.indexOf("propose"),
                "passages come between the read and the proposal");
        check(Json.canon(pev).contains("Passage 1 of " + np + ": 2 facts proposed, 1 quote found in the text"),
                "each passage reports what it proposed and how many quotes are really there");
        check(seenPassages.size() == np, "progress still reaches the caller too");

        section("bulk extract");
        World bw = new World();
        bw.src.files.put("third.md", "Third.\n- willow bark | contains | salicin\n");
        bw.e.scan(bw.src);
        final List<Runnable> queue = new ArrayList<>();
        final List<Map<String, Object>> saved = new ArrayList<>();
        final List<Map<String, Object>> statuses = new ArrayList<>();
        BulkRun.Agents pattern = (name, prog) -> new Agent.PatternAgent();
        BulkRun bulk = new BulkRun(bw.e, queue::add, statuses::add, sv -> saved.add(sv));
        List<String> all = Arrays.asList("src:handbook.md", "src:notes.md", "src:third.md");
        bulk.start(all, Arrays.asList("handbook.md", "notes.md", "third.md"), true, bw.src, pattern);
        check(queue.size() == 1, "starting queues one step, not the whole run");
        int maxOpen = 0, maxQueued = 0;
        while (!queue.isEmpty()) {
            Runnable r = queue.remove(0);
            r.run();
            maxOpen = Math.max(maxOpen, bw.e.directives().size());
            maxQueued = Math.max(maxQueued, queue.size());
        }
        Map<String, Object> st = bulk.status();
        check("done".equals(st.get("state")) && ((Number) st.get("extracted")).longValue() == 3, "all three sources are extracted: " + st.get("state"));
        check(bw.e.results(10).size() == 3, "each source gets its own signed result");
        check(maxOpen == 0 && maxQueued <= 1, "one directive at a time, issued at its turn, and one step queued at a time");
        check(((Number) st.get("kept")).longValue() == 6 && ((Number) st.get("refused")).longValue() == 1, "kept and refused are totalled across the run");
        check(saved.get(saved.size() - 1) == null, "a finished run leaves nothing to resume");
        check(bw.e.extracted("src:notes.md") && !bw.e.extracted("src:nope"), "the engine knows which sources are already extracted");

        bulk.clear();
        bulk.start(all, all, true, bw.src, pattern);
        while (!queue.isEmpty()) queue.remove(0).run();
        check(((Number) bulk.status().get("skipped")).longValue() == 3 && bw.e.results(10).size() == 3, "a second run skips sources already extracted");
        bw.src.files.put("third.md", "Third, edited.\n- willow bark | contains | salicin\n- salicin | is_a | glycoside\n");
        bw.e.scan(bw.src);
        bulk.clear();
        bulk.start(all, all, true, bw.src, pattern);
        while (!queue.isEmpty()) queue.remove(0).run();
        check(((Number) bulk.status().get("extracted")).longValue() == 1 && ((Number) bulk.status().get("skipped")).longValue() == 2,
                "a source whose file changed is extracted again, the rest skipped");

        bulk.clear();
        saved.clear();
        bulk.start(all, all, false, bw.src, pattern);
        queue.remove(0).run();
        bulk.pause();
        while (!queue.isEmpty()) queue.remove(0).run();
        check("paused".equals(bulk.status().get("state")) && ((Number) bulk.status().get("next")).longValue() == 1,
                "pause stops after the source being read");
        Map<String, Object> resumeFrom = saved.get(saved.size() - 1);
        check(resumeFrom != null && ((Number) resumeFrom.get("next")).longValue() == 1, "the queue is saved after every source");
        BulkRun later = new BulkRun(bw.e, queue::add, null, null);
        later.restore(Json.obj(Json.canon(resumeFrom)));
        check("interrupted".equals(later.status().get("state")), "a run the app didn't finish comes back as interrupted");
        int resultsBefore = bw.e.results(50).size();
        later.resume(bw.src, pattern);
        while (!queue.isEmpty()) queue.remove(0).run();
        check("done".equals(later.status().get("state")) && bw.e.results(50).size() == resultsBefore + 2,
                "resuming carries on from where it stopped, without redoing the first source");
        bulk.cancel();
        check("cancelled".equals(bulk.status().get("state")) && saved.get(saved.size() - 1) == null, "cancel keeps what was committed and forgets the rest");

        bulk.clear();
        bw.e.pause();
        bulk.start(all, all, false, bw.src, pattern);
        while (!queue.isEmpty()) queue.remove(0).run();
        Map<String, Object> ps2 = bulk.status();
        check("paused".equals(ps2.get("state")) && String.valueOf(ps2.get("why")).contains("paused by the steward")
                && ((Number) ps2.get("next")).longValue() == 1, "when the steward pauses the arbiters, the bulk run pauses too");
        bw.e.resume();
        rejects(() -> bulk.start(all, all, false, bw.src, pattern), "already going", "one bulk run at a time");

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

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Object o) { return (Map<String, Object>) o; }

    static long seqOf(List<Object> evs) {
        return evs.isEmpty() ? 0 : ((Number) ((Map<?, ?>) evs.get(evs.size() - 1)).get("seq")).longValue();
    }

    static List<Object> stepsOf(List<Object> evs) {
        List<Object> out = new ArrayList<>();
        for (Object o : evs) out.add(((Map<?, ?>) o).get("step"));
        return out;
    }

    static void section(String name) { System.out.println(); System.out.println(name); }
}
