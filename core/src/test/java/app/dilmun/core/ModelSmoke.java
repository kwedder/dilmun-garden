package app.dilmun.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A real model, a question with a known answer: does it answer sensibly?
 * Catches a model file, or a llama.cpp code path on some processor, that
 * loads fine but writes nonsense (as F16 MiniCPM5 did on one tablet).
 *
 *   java -Ddilmun.llm=<libdilmun_llm.so> -cp <core classes> app.dilmun.core.ModelSmoke model.gguf [more.gguf ...]
 */
public final class ModelSmoke {
    public static void main(String[] args) {
        System.load(System.getProperty("dilmun.llm"));
        Llama.init(null);
        int failed = 0;
        for (String path : args) {
            System.out.println("\n" + path);
            try (Llama m = Llama.load(path, "model:smoke", 2048, Llama.defaultThreads())) {
                Map<String, Object> info = m.info();
                System.out.println("  " + info.get("desc") + " - arch " + info.get("arch") + " - tokenizer " + info.get("tokenizer")
                        + " / " + info.get("pre") + " - template " + info.get("template"));
                System.out.println("  " + info.get("system"));
                List<String[]> msgs = new ArrayList<>();
                msgs.add(new String[]{"system", "You answer in one word."});
                msgs.add(new String[]{"user", "What is the capital of France?"});
                String a = m.generate(msgs, 48, 0f, false, null);
                Map<String, Object> st = m.stats();
                double tps = ((Number) st.get("gen_ms")).doubleValue() > 0
                        ? ((Number) st.get("gen_tokens")).doubleValue() / (((Number) st.get("gen_ms")).doubleValue() / 1000) : 0;
                boolean ok = a.toLowerCase(Locale.ROOT).contains("paris");
                System.out.println("  answer: " + a.replace('\n', ' ').trim());
                System.out.println(String.format(Locale.ROOT, "  %.1f tokens/s", tps));
                System.out.println("  " + (ok ? "ok    answers Paris" : "FAIL  expected Paris: the output is wrong for this file on this processor"));
                if (!ok) failed++;
            }
        }
        if (failed > 0) System.exit(1);
    }
}
