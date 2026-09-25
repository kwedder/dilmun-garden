package app.dilmun.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How well a model does as the arbiters' verifier: for each labelled pair of
 * sentences (tools/verify-bench.json) it gets the same briefing and the same
 * question a review asks ("same thing?"), in both orders of the sentences, under
 * the yes/no grammar. The arbiters' rule turns the two answers into agree,
 * disagree or inconsistent. What matters most is false agreement: a verifier
 * that says two different claims agree would settle claims that shouldn't be.
 *
 *   java -Ddilmun.llm=<libdilmun_llm.so> -cp <core classes> app.dilmun.core.VerifyBench model.gguf bench.json
 */
public final class VerifyBench {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        System.load(System.getProperty("dilmun.llm"));
        Llama.init(null);
        Map<String, Object> bench = Json.obj(new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8));
        List<Object> pairs = (List<Object>) bench.get("pairs");
        String name = Paths.get(args[0]).getFileName().toString();
        try (Llama m = Llama.load(args[0], "model:bench", 2048, Llama.defaultThreads())) {
            Verifier v = new Verifier.Model(m);
            String brief = Briefing.verify();
            int sameN = 0, diffN = 0, agreeOnSame = 0, agreeOnDiff = 0, disagreeOnDiff = 0, inconsistent = 0, rawRight = 0;
            long t0 = System.currentTimeMillis();
            for (Object o : pairs) {
                Map<String, Object> p = (Map<String, Object>) o;
                List<String> about = (List<String>) (List<?>) p.get("about");
                String a = (String) p.get("a"), b = (String) p.get("b");
                boolean same = "same".equals(p.get("label"));
                String s = v.judge(brief, Briefing.agree(a, b, about)), d = v.judge(brief, Briefing.agree(b, a, about));
                String result = "yes".equals(s) && "yes".equals(d) ? "agree" : "no".equals(s) && "no".equals(d) ? "disagree" : "inconsistent";
                if (same) { sameN++; if ("agree".equals(result)) agreeOnSame++; }
                else { diffN++; if ("agree".equals(result)) agreeOnDiff++; if ("disagree".equals(result)) disagreeOnDiff++; }
                if ("inconsistent".equals(result)) inconsistent++;
                if (("yes".equals(s)) == same) rawRight++;
                boolean right = same ? "agree".equals(result) : !"agree".equals(result);
                System.out.println(String.format(Locale.ROOT, "  %-5s %-9s same=%-3s swapped=%-3s %-12s %s | %s",
                        right ? "ok" : "WRONG", p.get("label"), s, d, result, clip(a), clip(b)));
            }
            double secs = (System.currentTimeMillis() - t0) / 1000.0;
            System.out.println(String.format(Locale.ROOT,
                    "BENCH %s | real matches caught %d/%d | false agreements %d/%d | differences caught %d/%d | inconsistent %d/%d | first answer right %d/%d | %.1f s per pair",
                    name, agreeOnSame, sameN, agreeOnDiff, diffN, disagreeOnDiff, diffN, inconsistent, pairs.size(), rawRight, pairs.size(),
                    secs / pairs.size()));
        }
    }

    private static String clip(String s) { return s.length() > 48 ? s.substring(0, 47) + "…" : s; }
}
