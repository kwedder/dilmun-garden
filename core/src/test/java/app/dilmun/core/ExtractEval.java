package app.dilmun.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs a real model over real text through the real arbiters, and prints every
 * fact it proposed with the verdict: kept, or refused and why. Refusals the
 * grounding rules made are marked, so you can see what they catch that the
 * quote check alone would have let through.
 *
 *   java -Ddilmun.llm=<libdilmun_llm.so> -cp <core classes> app.dilmun.core.ExtractEval model.gguf TEXT_DIR
 *
 * Every line starting with FACT is one JSON object, for tools to read.
 */
public final class ExtractEval {
    /** Refusals that come before grounding: these facts would be refused either way. */
    static final Set<String> BEFORE = new HashSet<>(Arrays.asList("quote does not match the source", "the fact is incomplete", "over budget"));

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        System.load(System.getProperty("dilmun.llm"));
        Llama.init(null);
        final long t0 = System.currentTimeMillis();
        Engine e = Engine.open(new CoreTest.MemBackend(), new Crypto.SoftSigner(), new Crypto.SoftSigner(),
                () -> t0, new Agent.PatternAgent());   // a still clock: a slow runner never meets a directive's expiry
        DevServer.DirSources src = new DevServer.DirSources(new File(args[1]));
        e.scan(src);
        int kept = 0, grounding = 0, other = 0;
        try (Llama m = Llama.load(args[0], "model:eval", 4096, Llama.defaultThreads())) {
            Map<String, Object> enlisted = e.present(m.id());
            System.out.println("Enlisted under briefings " + enlisted.get("briefings"));
            ModelAgent agent = new ModelAgent(m, Policy.SCHEMA_ORDER, new ModelAgent.Progress() {
                @Override public void passage(int i, int n) { System.out.println("  passage " + i + " of " + n); }
                @Override public void text(String piece) { }
            });
            for (Object o : e.sources()) {
                Map<String, Object> s = (Map<String, Object>) o;
                System.out.println("\n" + s.get("name"));
                List<String> notes = e.notes(m.id());
                if (!notes.isEmpty()) System.out.println("  arbiters' notes: " + notes);
                boolean rulesOnly = "rules".equals(System.getProperty("dilmun.extract"));   // skip the model's extraction: claims come from the arbiters anyway
                Map<String, Object> r = e.extract((String) s.get("id"), src, rulesOnly ? new Agent.PatternAgent() : agent.briefed(notes));
                Map<String, Object> p = (Map<String, Object>) Tx.payload(e.store().get((String) r.get("tx"))).get("proposal");
                List<Object> facts = (List<Object>) p.get("facts");
                String[] why = new String[facts.size()];
                for (Object x : (List<Object>) r.get("rejected")) {
                    Map<String, Object> rj = (Map<String, Object>) x;
                    why[((Number) rj.get("i")).intValue()] = (String) rj.get("reason");
                }
                for (int i = 0; i < facts.size(); i++) {
                    Map<String, Object> f = (Map<String, Object>) facts.get(i);
                    String verdict = why[i] == null ? "kept" : BEFORE.contains(why[i]) || why[i].startsWith("attribute not") ? "refused" : "refused-grounding";
                    if (why[i] == null) kept++; else if (verdict.equals("refused")) other++; else grounding++;
                    System.out.println("FACT " + Json.canon(Tx.m("source", s.get("name"), "e", ((List<Object>) f.get("ident")).get(1),
                            "a", f.get("a"), "v", f.get("v"), "quote", ((Map<String, Object>) f.get("quote")).get("text"),
                            "verdict", verdict, "reason", why[i], "by", f.containsKey("by") ? f.get("by") : "model")));
                }
                if (p.get("raw") instanceof List)
                    for (Object raw : (List<Object>) p.get("raw"))
                        System.out.println("RAW " + Json.canon(raw));
            }
            // the arbiters go through the claims and delegate yes/no checks to the same model
            System.out.println("\nClaims written: " + e.summary().get("claims"));
            Map<String, Object> rv = e.review(new Verifier.Model(m), Engine.REVIEW_LIMIT);
            System.out.println("Review: " + rv);
            for (Map<String, Object> v : e.state().verdicts) {
                List<Object> cs = (List<Object>) v.get("claims");
                System.out.println("VERDICT " + Json.canon(Tx.m("answer", v.get("answer"), "same", v.get("same"), "different", v.get("different"),
                        "counts", v.get("counts"), "why", v.get("why"),
                        "a", e.state().claims.get(cs.get(0)).get("text"), "b", e.state().claims.get(cs.get(1)).get("text"))));
            }
        }
        System.out.println("\nStored, as concepts:");
        for (Object o : e.held()) {
            Map<String, Object> h = (Map<String, Object>) o;
            System.out.println("STORED " + h.get("entity") + " | " + h.get("a") + " | " + h.get("v"));
        }
        System.out.println(String.format(Locale.ROOT, "\n%d kept, %d refused by grounding, %d refused by the quote/schema checks",
                kept, grounding, other));
    }
}
