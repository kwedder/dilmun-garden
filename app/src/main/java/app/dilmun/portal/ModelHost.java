package app.dilmun.portal;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

import app.dilmun.core.Crypto;
import app.dilmun.core.Llama;
import app.dilmun.core.Tx;

/**
 * The model file on this device: imported once from a file the user picked,
 * checked, hashed, and kept in the app's private storage. Loads lazily and
 * unloads on request. The app has no internet permission; the file comes in
 * through the system file picker.
 */
final class ModelHost {
    interface Progress { void bytes(long done, long total); }

    static final int CONTEXT = 4096;

    private final Context context;
    private final SharedPreferences prefs;
    private volatile Llama llama;
    private volatile boolean loading;
    private volatile String lastError;
    private volatile int loadedThreads;

    ModelHost(Context context, SharedPreferences prefs) {
        this.context = context;
        this.prefs = prefs;
    }

    Llama llama() { return llama; }
    boolean loaded() { return llama != null; }

    private File dir() {
        File d = new File(context.getFilesDir(), "models");
        if (!d.isDirectory() && !d.mkdirs()) throw new IllegalStateException("could not create the models folder");
        return d;
    }

    File file() {
        String name = prefs.getString("model.file", null);
        if (name == null) return null;
        File f = new File(dir(), name);
        return f.isFile() ? f : null;
    }

    /** "model:<name>:<first 12 of sha256>": what every result made with this file records. */
    String id() {
        return "model:" + prefs.getString("model.name", "?") + ":" + prefs.getString("model.sha", "").substring(0, 12);
    }

    Map<String, Object> status() {
        Map<String, Object> s = new TreeMap<>();
        File f = file();
        s.put("file", f == null ? null : Tx.m(
                "name", prefs.getString("model.name", f.getName()),
                "bytes", f.length(),
                "sha", prefs.getString("model.sha", ""),
                "id", id()));
        s.put("loaded", llama != null);
        s.put("loading", loading);
        s.put("error", lastError);
        s.put("useForExtraction", prefs.getBoolean("model.extract", true));
        s.put("info", llama == null ? null : llama.info());
        s.put("threads", (long) (llama != null ? loadedThreads : threads()));
        s.put("threadsSetting", (long) prefs.getInt("model.threads", 0));
        s.put("threadsAuto", (long) Llama.defaultThreads());
        s.put("cores", (long) Runtime.getRuntime().availableProcessors());
        return s;
    }

    /** Threads to run the model on: the user's choice, or a safe default for this phone (0 = automatic). */
    int threads() {
        int n = prefs.getInt("model.threads", 0);
        int cores = Runtime.getRuntime().availableProcessors();
        return n <= 0 ? Llama.defaultThreads() : Math.max(1, Math.min(cores, n));
    }

    /** Takes effect the next time the model loads. */
    void setThreads(int n) {
        int cores = Runtime.getRuntime().availableProcessors();
        if (n < 0 || n > cores) throw new IllegalArgumentException("choose between 1 and " + cores + " threads, or automatic");
        prefs.edit().putInt("model.threads", n).apply();
    }

    /** Room needed on top of the file itself, so the phone isn't left completely full. */
    static final long SPARE_BYTES = 200L << 20;

    /** Copy the picked file in, checking that it's a GGUF model and hashing it on the way. */
    Map<String, Object> importFrom(ContentResolver cr, Uri uri, String displayName, long size, Progress p) throws Exception {
        File tmp = new File(dir(), "import.partial");
        if (tmp.exists() && !tmp.delete()) tmp.deleteOnExit();                // a copy an earlier import left behind
        long free = dir().getUsableSpace();
        if (size > 0 && free < size + SPARE_BYTES)
            throw new IllegalStateException("not enough free space: this file needs " + gb(size + SPARE_BYTES)
                    + " and the phone has " + gb(free) + " free. The downloaded copy can be deleted once it's imported.");
        unload();
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long done = 0;
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IllegalStateException("could not open the file");
        try {
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[1 << 20];
                int n;
                boolean first = true;
                long lastReport = 0;
                while ((n = in.read(buf)) > 0) {
                    if (first) {
                        if (n < 4 || buf[0] != 'G' || buf[1] != 'G' || buf[2] != 'U' || buf[3] != 'F')
                            throw new IllegalStateException("this isn't a GGUF model file");
                        first = false;
                    }
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    done += n;
                    if (done - lastReport > (16 << 20)) { lastReport = done; p.bytes(done, size); }
                }
            } finally {
                out.close();
            }
        } catch (Exception e) {
            if (!tmp.delete()) tmp.deleteOnExit();
            throw e;
        } finally {
            in.close();
        }
        String hex = Crypto.hex(sha.digest());
        File old = file();
        String safe = displayName == null ? "model.gguf" : displayName.replaceAll("[^A-Za-z0-9._-]", "_");
        File dest = new File(dir(), safe);
        if (old != null && !old.equals(dest) && !old.delete()) old.deleteOnExit();
        if (dest.exists() && !dest.delete()) throw new IllegalStateException("could not replace the old model file");
        if (!tmp.renameTo(dest)) throw new IllegalStateException("could not store the model file");
        String name = safe.endsWith(".gguf") ? safe.substring(0, safe.length() - 5) : safe;
        prefs.edit().putString("model.file", safe).putString("model.name", name).putString("model.sha", hex).apply();
        p.bytes(done, size);
        return status();
    }

    synchronized Map<String, Object> load() {
        if (llama != null) return status();
        File f = file();
        if (f == null) throw new IllegalStateException("no model file yet: import one first");
        loading = true;
        lastError = null;
        try {
            System.loadLibrary("dilmun_llm");
            Llama.init(context.getApplicationInfo().nativeLibraryDir);
            int t = threads();
            llama = Llama.load(f.getAbsolutePath(), id(), CONTEXT, t);
            loadedThreads = t;
        } catch (UnsatisfiedLinkError e) {
            lastError = "this build has no model runtime for this phone's processor";
            throw new IllegalStateException(lastError);
        } catch (RuntimeException e) {
            lastError = e.getMessage();
            throw e;
        } finally {
            loading = false;
        }
        return status();
    }

    synchronized void unload() {
        Llama l = llama;
        llama = null;
        if (l != null) { l.stop(); l.close(); }
    }

    void remove() {
        unload();
        File f = file();
        if (f != null && !f.delete()) f.deleteOnExit();
        prefs.edit().remove("model.file").remove("model.name").remove("model.sha").apply();
    }

    private static String gb(long n) { return String.format(java.util.Locale.ROOT, "%.1f GB", n / 1e9); }

    void setUseForExtraction(boolean on) { prefs.edit().putBoolean("model.extract", on).apply(); }
    boolean useForExtraction() { return prefs.getBoolean("model.extract", true); }
}
