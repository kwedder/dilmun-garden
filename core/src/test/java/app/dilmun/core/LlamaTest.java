package app.dilmun.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Tests the native model layer on a desktop:
 *   java -Ddilmun.llm=/path/libdilmun_llm.so app.dilmun.core.LlamaTest model.gguf [model2.gguf]
 * Any GGUF works; a tiny random-weight model is enough to exercise every path.
 */
public final class LlamaTest {
    static int passed = 0, failed = 0;

    static void check(boolean ok, String what) {
        if (ok) { passed++; System.out.println("  ok    " + what); }
        else { failed++; System.out.println("  FAIL  " + what); }
    }

    public static void main(String[] args) throws Exception {
        System.load(System.getProperty("dilmun.llm"));
        Llama.init(null);
        for (String path : args) {
            System.out.println("\n" + path);
            Llama m = Llama.load(path, "model:test", 1024, 2);
            Map<String, Object> info = m.info();
            check(info.containsKey("arch") && ((Number) info.get("n_ctx")).intValue() == 1024, "loads, and reports " + info.get("arch") + " with a 1024-token context");
            check(info.get("tokenizer") instanceof String && info.containsKey("pre") && ((Number) info.get("n_vocab")).intValue() > 0,
                    "reports its tokenizer for the diagnostics: " + info.get("tokenizer") + ", pre-tokenizer '" + info.get("pre") + "', " + info.get("n_vocab") + " tokens");
            check(m.countTokens("aspirin treats fever") > 0, "counts tokens");

            List<String[]> msgs = new ArrayList<>();
            msgs.add(new String[]{"system", "You answer briefly."});
            msgs.add(new String[]{"user", "Does aspirin treat fever? ☕ 🌡️ ünïcödé"});
            final ByteArrayOutputStream streamed = new ByteArrayOutputStream();
            final int[] pieces = {0};
            String out = m.generate(msgs, 40, 0.7f, false, piece -> {
                pieces[0]++;
                streamed.write(piece, 0, piece.length);
                return true;
            });
            Map<String, Object> st = m.stats();
            check(((Number) st.get("prompt_tokens")).intValue() > 10, "the prompt went through the chat template (" + st.get("prompt_tokens") + " tokens)");
            check(new String(streamed.toByteArray(), StandardCharsets.UTF_8).equals(out), "streamed pieces add up to the returned text");
            check(((Number) st.get("gen_tokens")).intValue() > 0 && pieces[0] > 0, "generated " + st.get("gen_tokens") + " tokens in " + st.get("gen_ms") + " ms, stop: " + st.get("stop"));

            String greedy1 = m.generate(msgs, 20, 0f, false, null);
            String greedy2 = m.generate(msgs, 20, 0f, false, null);
            check(greedy1.equals(greedy2), "temperature 0 is repeatable");

            List<String[]> pre = new ArrayList<>(msgs);
            pre.add(new String[]{"prefill", "The memory says: [1] aspirin treats fever.\n[1]"});
            final ByteArrayOutputStream preStreamed = new ByteArrayOutputStream();
            String thought = m.generate(pre, 10, 0f, true, piece -> { preStreamed.write(piece, 0, piece.length); return true; });
            check(thought.contains("<think>") && thought.contains("The memory says: [1] aspirin treats fever.\n[1]")
                    && new String(preStreamed.toByteArray(), StandardCharsets.UTF_8).equals(thought),
                    "with think on, a prefill opens the thinking block and is returned and streamed as its start");
            String plain = m.generate(pre, 10, 0f, false, null);
            check(plain.startsWith("The memory says:") && !plain.contains("<think>"), "without think, the reply simply starts with the prefill");

            final int[] seen = {0};
            m.generate(msgs, 200, 0.7f, false, piece -> ++seen[0] < 5);
            check(seen[0] == 5 && "stopped".equals(m.stats().get("stop")), "the sink can stop generation");

            Thread t = new Thread(() -> { try { Thread.sleep(30); } catch (InterruptedException ignored) { } m.stop(); });
            t.start();
            m.generate(msgs, 100000, 0.7f, false, piece -> true);
            t.join();
            check(Arrays.asList("stopped", "context full").contains(m.stats().get("stop")), "stop() from another thread ends a long generation (" + m.stats().get("stop") + ")");

            StringBuilder big = new StringBuilder();
            for (int i = 0; i < 3000; i++) big.append("aspirin treats fever. ");
            List<String[]> tooLong = new ArrayList<>();
            tooLong.add(new String[]{"user", big.toString()});
            boolean refused = false;
            try { m.generate(tooLong, 10, 0f, false, null); } catch (IllegalStateException e) { refused = e.getMessage().contains("fit"); }
            check(refused, "a prompt longer than the context is refused, not truncated silently");

            m.close();
            boolean closed = false;
            try { m.generate(msgs, 5, 0f, false, null); } catch (IllegalStateException e) { closed = true; }
            check(closed, "a closed model refuses to generate");
        }
        boolean bad = false;
        try { Llama.load("/dev/null", "x", 512, 1); } catch (IllegalStateException e) { bad = true; }
        check(bad, "a file that isn't a model is refused");
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
