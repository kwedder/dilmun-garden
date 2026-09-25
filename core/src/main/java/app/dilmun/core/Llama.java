package app.dilmun.core;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * A model file loaded with llama.cpp, running on this device's CPU.
 * The native library (libdilmun_llm) must be loaded by the caller first:
 * System.loadLibrary("dilmun_llm") on Android, System.load(path) on a desktop.
 *
 * One generation at a time; calls are serialized on this object.
 */
public final class Llama implements AutoCloseable, Llm {

    /** Receives text as it is generated. Return false to stop. */
    public interface Sink {
        boolean onPiece(byte[] utf8);
    }

    private static boolean initialized;
    private volatile long handle;
    private final String id;
    private final Map<String, Object> info;

    private Llama(long handle, String id) {
        this.handle = handle;
        this.id = id;
        this.info = Json.obj(new String(nativeInfo(handle), StandardCharsets.UTF_8));
    }

    /** Load backends once. On Android, pass the app's native library directory. */
    public static synchronized void init(String backendDir) {
        if (initialized) return;
        nativeInit(backendDir == null ? null : backendDir.getBytes(StandardCharsets.UTF_8));
        initialized = true;
    }

    /**
     * @param id how results name this model, e.g. "model:MiniCPM5-1B-Q4_K_M:3f9a2c1b0e7d" (its file hash)
     */
    public static Llama load(String path, String id, int nCtx, int nThreads) {
        init(null);
        long h = nativeLoad(path.getBytes(StandardCharsets.UTF_8), nCtx, nThreads);
        if (h == 0) throw new IllegalStateException("llama.cpp could not load this file as a model");
        return new Llama(h, id);
    }

    public static int defaultThreads() {
        int n = Runtime.getRuntime().availableProcessors();
        return Math.max(2, Math.min(4, n - 2));
    }

    @Override public String id() { return id; }

    public Map<String, Object> info() { return info; }

    public synchronized int countTokens(String text) {
        check();
        return nativeCountTokens(handle, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Generate a reply to the messages, each {role, content}. A last message with
     * role "prefill" is not sent as a turn: the reply starts with it (inside the
     * thinking block when think is on), and the returned text includes it.
     * @param think let a reasoning model think before answering
     */
    @Override public synchronized String generate(List<String[]> messages, int maxTokens, float temp, boolean think, Sink sink) {
        check();
        byte[][] roles = new byte[messages.size()][], contents = new byte[messages.size()][];
        for (int i = 0; i < messages.size(); i++) {
            roles[i] = messages.get(i)[0].getBytes(StandardCharsets.UTF_8);
            contents[i] = messages.get(i)[1].getBytes(StandardCharsets.UTF_8);
        }
        byte[] out = nativeGenerate(handle, roles, contents, maxTokens, temp, think, sink);
        if (out == null) throw new IllegalStateException("the model could not read the prompt: " + stats().get("stop"));
        return new String(out, StandardCharsets.UTF_8);
    }

    /** Stop the generation in progress. Safe to call from any thread. */
    @Override public void stop() {
        long h = handle;
        if (h != 0) nativeStop(h);
    }

    /** prompt_tokens, gen_tokens, prompt_ms, gen_ms and stop reason of the last generation. */
    @Override public synchronized Map<String, Object> stats() {
        check();
        return Json.obj(new String(nativeStats(handle), StandardCharsets.UTF_8));
    }

    @Override public int contextTokens() { return ((Number) info.get("n_ctx")).intValue(); }

    @Override public synchronized void close() {
        if (handle != 0) {
            nativeFree(handle);
            handle = 0;
        }
    }

    private void check() {
        if (handle == 0) throw new IllegalStateException("the model is not loaded");
    }

    private static native void nativeInit(byte[] backendDir);
    private static native long nativeLoad(byte[] path, int nCtx, int nThreads);
    private static native byte[] nativeInfo(long handle);
    private static native int nativeCountTokens(long handle, byte[] text);
    private static native void nativeStop(long handle);
    private static native byte[] nativeGenerate(long handle, byte[][] roles, byte[][] contents,
                                                int maxTokens, float temp, boolean think, Sink sink);
    private static native byte[] nativeStats(long handle);
    private static native void nativeFree(long handle);
}
