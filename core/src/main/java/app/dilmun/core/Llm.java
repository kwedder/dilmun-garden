package app.dilmun.core;

import java.util.List;
import java.util.Map;

/** A language model the arbiters can deploy. {@link Llama} is the real one; tests use a fake. */
public interface Llm {
    /** Names the exact model file, e.g. "model:MiniCPM5-1B-Q4_K_M:3f9a2c1b0e7d". */
    String id();

    /** A reply to the messages, each {role, content}. */
    String generate(List<String[]> messages, int maxTokens, float temp, boolean think, Llama.Sink sink);

    void stop();

    Map<String, Object> stats();

    int contextTokens();
}
