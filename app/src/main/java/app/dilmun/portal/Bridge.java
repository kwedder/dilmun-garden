package app.dilmun.portal;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import app.dilmun.core.Agent;
import app.dilmun.core.Ask;
import app.dilmun.core.Engine;
import app.dilmun.core.Json;
import app.dilmun.core.Llama;
import app.dilmun.core.ModelAgent;
import app.dilmun.core.Policy;
import app.dilmun.core.Store;
import app.dilmun.core.Tx;

/**
 * What the screens can ask for. The screens are HTML in a WebView; every call
 * comes here and goes to the engine, and every answer is canonical JSON:
 * {"ok": value} or {"error": "what went wrong"}. The screens never write to
 * the log themselves.
 *
 * Quick calls answer directly. Slow ones (extracting with the model, asking,
 * importing or loading a model) return a job number at once and report
 * through events: window.dilmunEvent("job", {...}) and ("token", {...}).
 */
final class Bridge {
    private final MainActivity activity;
    private final WebView web;
    private final SharedPreferences prefs;
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final AtomicLong jobs = new AtomicLong();
    private final ModelHost model;
    private Engine engine;
    private TreeSources folder;
    private AssetSources samples;

    Bridge(MainActivity activity, WebView web) {
        this.activity = activity;
        this.web = web;
        this.prefs = activity.getSharedPreferences("dilmun", Context.MODE_PRIVATE);
        this.model = new ModelHost(activity, prefs);
        String tree = prefs.getString("tree", null);
        if (tree != null) folder = new TreeSources(activity.getContentResolver(), Uri.parse(tree));
        if (prefs.getBoolean("samples", false)) samples = new AssetSources(activity.getAssets());
    }

    private synchronized Engine engine() {
        if (engine == null) {
            engine = Engine.open(new SqliteBackend(activity),
                    new KeystoreSigner("dilmun-portal"), new KeystoreSigner("dilmun-steward"),
                    Engine.SYSTEM_CLOCK, new Agent.PatternAgent());
        }
        return engine;
    }

    void close() {
        model.unload();
        work.shutdownNow();
    }

    /** Samples and the picked folder together. */
    private Engine.Sources readable() {
        final TreeSources f = folder;
        final AssetSources s = samples;
        if (f == null && s == null) throw new Store.Rejected("no sources yet: choose a folder or add the sample texts");
        return new Engine.Sources() {
            @Override public List<Map<String, Object>> list() {
                List<Map<String, Object>> out = new ArrayList<>();
                if (s != null) out.addAll(s.list());
                if (f != null) out.addAll(f.list());
                return out;
            }
            @Override public String read(String id) {
                if (id.startsWith("sample:")) return s == null ? null : s.read(id);
                return f == null ? null : f.read(id);
            }
        };
    }

    // ------------------------------------------------------------ plumbing

    private interface Call { Object run() throws Exception; }

    private static String call(Call c) {
        try {
            return Json.canon(Tx.m("ok", c.run()));
        } catch (Store.Rejected e) {
            return Json.canon(Tx.m("error", e.getMessage()));
        } catch (Exception e) {
            return Json.canon(Tx.m("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } catch (OutOfMemoryError e) {
            return Json.canon(Tx.m("error", "the phone ran out of memory"));
        }
    }

    private void emit(final String event, final Object payload) {
        final String json = Json.canon(payload);
        web.post(new Runnable() {
            @Override public void run() {
                web.evaluateJavascript("window.dilmunEvent && window.dilmunEvent(" + Json.canon(event) + "," + json + ")", null);
            }
        });
    }

    /** Run slow work in order on one worker thread; report progress, then done or error. */
    private long job(final String kind, final Call body) {
        final long id = jobs.incrementAndGet();
        work.execute(new Runnable() {
            @Override public void run() {
                String r = call(body);
                Map<String, Object> m = Json.obj(r);
                Map<String, Object> ev = Tx.m("id", id, "kind", kind, "state", m.containsKey("error") ? "error" : "done");
                if (m.containsKey("error")) ev.put("error", m.get("error"));
                else ev.put("result", m.get("ok"));
                emit("job", ev);
            }
        });
        return id;
    }

    private void progress(long id, String kind, Map<String, Object> fields) {
        Map<String, Object> ev = Tx.m("id", id, "kind", kind, "state", "progress");
        ev.putAll(fields);
        emit("job", ev);
    }

    // ------------------------------------------------------------ reads

    @JavascriptInterface public String summary() {
        return call(() -> {
            Map<String, Object> s = new TreeMap<>(engine().summary());
            s.put("folder", folder == null ? null : folder.label());
            s.put("samples", samples != null);
            Map<String, Object> ms = model.status();
            Map<?, ?> file = (Map<?, ?>) ms.get("file");
            s.put("model", Tx.m("name", file == null ? null : file.get("name"), "id", file == null ? null : file.get("id"),
                    "loaded", ms.get("loaded"), "extract", ms.get("useForExtraction")));
            s.put("agent", extractionAgentName());
            return s;
        });
    }

    @JavascriptInterface public String sources() { return call(() -> engine().sources()); }
    @JavascriptInterface public String directives() { return call(() -> engine().directives()); }
    @JavascriptInterface public String results(int limit) { return call(() -> engine().results(limit)); }
    @JavascriptInterface public String memory(String query) { return call(() -> engine().memory(query)); }
    @JavascriptInterface public String held() { return call(() -> engine().held()); }
    @JavascriptInterface public String log(int offset, int limit) { return call(() -> engine().log(offset, limit)); }
    @JavascriptInterface public String tx(String id) { return call(() -> Json.parse(engine().tx(id))); }
    @JavascriptInterface public String verify() { return call(() -> engine().verify()); }
    @JavascriptInterface public String modelStatus() { return call(model::status); }

    // ------------------------------------------------------------ quick actions

    @JavascriptInterface public String rescan() { return call(() -> engine().scan(readable())); }
    @JavascriptInterface public String gate() { return call(() -> engine().gate()); }
    @JavascriptInterface public String reconcile() { return call(() -> engine().reconcile()); }
    @JavascriptInterface public String pause() { return call(() -> { engine().pause(); return true; }); }
    @JavascriptInterface public String resume() { return call(() -> { engine().resume(); return true; }); }

    @JavascriptInterface public String approve(String key) {
        return call(() -> { engine().approve(Collections.singletonList(key)); return engine().gate(); });
    }

    @JavascriptInterface public String useSamples() {
        prefs.edit().putBoolean("samples", true).apply();
        samples = new AssetSources(activity.getAssets());
        return call(() -> engine().scan(readable()));
    }

    @JavascriptInterface public void pickFolder() {
        activity.runOnUiThread(new Runnable() { @Override public void run() { activity.pickFolder(); } });
    }

    void onTreePicked(Uri tree) {
        prefs.edit().putString("tree", tree.toString()).apply();
        folder = new TreeSources(activity.getContentResolver(), tree);
        job("scan", () -> engine().scan(readable()));
    }

    // ------------------------------------------------------------ extraction

    private String extractionAgentName() {
        return model.loaded() && model.useForExtraction() ? model.id() : new Agent.PatternAgent().id();
    }

    /** Extract from one source with the model if it's loaded (and allowed), else the pattern agent. */
    @JavascriptInterface public String extract(final String sourceId) {
        return call(() -> {
            final long[] id = new long[1];
            id[0] = job("extract", () -> {
                Agent agent;
                Llama l = model.llama();
                if (l != null && model.useForExtraction()) {
                    agent = new ModelAgent(l, Policy.SCHEMA_ORDER, new ModelAgent.Progress() {
                        @Override public void passage(int i, int n) { progress(id[0], "extract", Tx.m("passage", (long) i, "passages", (long) n)); }
                        @Override public void text(String piece) { }
                    });
                } else {
                    agent = new Agent.PatternAgent();
                }
                Map<String, Object> r = engine().extract(sourceId, readable(), agent);
                r.put("source", sourceId);
                return r;
            });
            return id[0];
        });
    }

    // ------------------------------------------------------------ the model

    @JavascriptInterface public void importModel() {
        activity.runOnUiThread(new Runnable() { @Override public void run() { activity.pickModel(); } });
    }

    void onModelPicked(final Uri uri) {
        final ContentResolver cr = activity.getContentResolver();
        String name = null;
        long size = -1;
        Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
        if (c != null) {
            try {
                if (c.moveToNext()) {
                    name = c.getString(0);
                    size = c.isNull(1) ? -1 : c.getLong(1);
                }
            } finally {
                c.close();
            }
        }
        final String fname = name;
        final long fsize = size;
        final long[] id = new long[1];
        id[0] = job("import", () -> {
            model.importFrom(cr, uri, fname, fsize, (done, total) ->
                    progress(id[0], "import", Tx.m("bytes", done, "total", total)));
            return model.load();
        });
    }

    @JavascriptInterface public String loadModel() { return call(() -> job("load", model::load)); }
    @JavascriptInterface public String unloadModel() { return call(() -> { model.unload(); return model.status(); }); }
    @JavascriptInterface public String removeModel() { return call(() -> { model.remove(); return model.status(); }); }
    @JavascriptInterface public String setUseModel(boolean on) { return call(() -> { model.setUseForExtraction(on); return model.status(); }); }

    /** Stop what the model is generating now. */
    @JavascriptInterface public String stop() {
        return call(() -> { Llama l = model.llama(); if (l != null) l.stop(); return true; });
    }

    /** Opens a model page in the phone's browser. The app itself never goes online. */
    @JavascriptInterface public void openLink(final String url) {
        if (url == null || !url.startsWith("https://huggingface.co/")) return;
        activity.runOnUiThread(new Runnable() { @Override public void run() { activity.openLink(url); } });
    }

    // ------------------------------------------------------------ ask

    /**
     * request: {question, history: [[role, content], ...], memory: bool, think: bool}.
     * Streams "token" events, then a "job" event with the answer, the facts it
     * was given, and timing. Nothing is written to the log.
     */
    @JavascriptInterface public String ask(final String request) {
        return call(() -> {
            final Map<String, Object> req = Json.obj(request);
            final Llama l = model.llama();
            if (l == null) throw new Store.Rejected(model.file() == null ? "no model yet: import one first" : "load the model first");
            final long[] id = new long[1];
            id[0] = job("ask", () -> {
                String q = String.valueOf(req.get("question"));
                boolean useMemory = !Boolean.FALSE.equals(req.get("memory"));
                boolean think = Boolean.TRUE.equals(req.get("think"));
                List<Object> facts = useMemory ? engine().recall(q, 8) : new ArrayList<Object>();
                List<String[]> history = new ArrayList<>();
                Object h = req.get("history");
                if (h instanceof List) for (Object o : (List<?>) h) {
                    List<?> t = (List<?>) o;
                    history.add(new String[]{String.valueOf(t.get(0)), String.valueOf(t.get(1))});
                }
                emit("job", Tx.m("id", id[0], "kind", "ask", "state", "progress", "facts", facts));
                final StringBuilder pending = new StringBuilder();
                final long[] last = {0};
                String answer = l.generate(Ask.messages(history, q, facts, useMemory), 768, 0.6f, think, piece -> {
                    pending.append(new String(piece, StandardCharsets.UTF_8));
                    long now = System.currentTimeMillis();
                    if (now - last[0] > 60) {
                        last[0] = now;
                        emit("token", Tx.m("id", id[0], "text", pending.toString()));
                        pending.setLength(0);
                    }
                    return true;
                });
                if (pending.length() > 0) emit("token", Tx.m("id", id[0], "text", pending.toString()));
                return Tx.m("text", answer, "facts", facts, "stats", l.stats(), "model", l.id());
            });
            return id[0];
        });
    }
}
