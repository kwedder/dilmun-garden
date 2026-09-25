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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import app.dilmun.core.Agent;
import app.dilmun.core.Ask;
import app.dilmun.core.BulkRun;
import app.dilmun.core.Engine;
import app.dilmun.core.Json;
import app.dilmun.core.Llama;
import app.dilmun.core.ModelAgent;
import app.dilmun.core.Policy;
import app.dilmun.core.Store;
import app.dilmun.core.Tx;
import app.dilmun.core.Verifier;

/**
 * What the screens can ask for. The screens are HTML in a WebView; every call
 * comes here and goes to the engine, and every answer is canonical JSON:
 * {"ok": value} or {"error": "what went wrong"}. The screens never write to
 * the log themselves.
 *
 * Quick calls answer directly. Slow ones (extracting with the model, asking,
 * importing or loading a model) return a job number at once and report
 * through events: window.dilmunEvent("job", {...}) and ("token", {...}).
 * A bulk run reports its state through ("bulk", {...}), and every step the
 * engine takes is announced with ("trace", null): the page then reads the
 * trace since the last step it saw.
 */
final class Bridge {
    private final MainActivity activity;
    private final WebView web;
    private final SharedPreferences prefs;
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final AtomicLong jobs = new AtomicLong();
    private final AtomicBoolean traceNudged = new AtomicBoolean();
    private final ModelHost model;
    private Engine engine;
    private BulkRun bulk;
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
            // New trace events: tell the page once, and let it read everything since its last seq.
            engine.onTrace(new Runnable() {
                @Override public void run() {
                    if (traceNudged.compareAndSet(false, true)) {
                        web.post(new Runnable() {
                            @Override public void run() {
                                traceNudged.set(false);
                                web.evaluateJavascript("window.dilmunEvent && window.dilmunEvent(\"trace\", null)", null);
                            }
                        });
                    }
                }
            });
        }
        return engine;
    }

    /** The bulk run, with any queue the app didn't finish last time restored as interrupted. */
    private synchronized BulkRun bulk() {
        if (bulk == null) {
            bulk = new BulkRun(engine(),
                    step -> work.execute(step),
                    status -> {
                        emit("bulk", status);
                        activity.keepAwake(Boolean.TRUE.equals(status.get("busy")) || "running".equals(status.get("state")));
                    },
                    saved -> {
                        if (saved == null) prefs.edit().remove("bulk").apply();
                        else prefs.edit().putString("bulk", Json.canon(saved)).apply();
                    });
            String saved = prefs.getString("bulk", null);
            if (saved != null) {
                try { bulk.restore(Json.obj(saved)); }
                catch (RuntimeException e) { prefs.edit().remove("bulk").apply(); }
            }
        }
        return bulk;
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
    @JavascriptInterface public String denied() { return call(() -> engine().denied()); }
    @JavascriptInterface public String claims(String query) { return call(() -> engine().claims(query)); }
    @JavascriptInterface public String queue() { return call(() -> engine().stewardQueue()); }
    /** The dream cycle's work, run by hand: re-check what's settled against today's rules. */
    @JavascriptInterface public String maintain() { return call(() -> engine().maintain()); }

    /**
     * The arbiters go through the held claims and delegate yes/no checks to the
     * loaded model. Runs as a job; the result says how many were asked and answered.
     */
    @JavascriptInterface public String review() {
        return call(() -> {
            final Llama l = model.llama();
            if (l == null) throw new Store.Rejected(model.file() == null ? "no model yet: import one first" : "load the model first");
            return job("review", () -> engine().review(new Verifier.Model(l), Engine.REVIEW_LIMIT));
        });
    }
    @JavascriptInterface public String log(int offset, int limit) { return call(() -> engine().log(offset, limit)); }
    @JavascriptInterface public String tx(String id) { return call(() -> Json.parse(engine().tx(id))); }
    @JavascriptInterface public String verify() { return call(() -> engine().verify()); }
    @JavascriptInterface public String modelStatus() { return call(model::status); }
    @JavascriptInterface public String trace(double since) { return call(() -> engine().trace((long) since)); }
    @JavascriptInterface public String extracted() {
        return call(() -> {
            List<Object> out = new ArrayList<>();
            for (Object o : engine().sources()) {
                String id = (String) ((Map<?, ?>) o).get("id");
                if (engine().extracted(id)) out.add(id);
            }
            return out;
        });
    }

    // ------------------------------------------------------------ quick actions

    @JavascriptInterface public String rescan() { return call(() -> engine().scan(readable())); }
    @JavascriptInterface public String gate() { return call(() -> engine().gate()); }
    @JavascriptInterface public String reconcile() { return call(() -> engine().reconcile()); }
    @JavascriptInterface public String pause() { return call(() -> { engine().pause(); return true; }); }
    @JavascriptInterface public String resume() { return call(() -> { engine().resume(); return true; }); }

    @JavascriptInterface public String approve(String key) {
        return call(() -> { engine().approve(Collections.singletonList(key)); return engine().gate(); });
    }

    @JavascriptInterface public String deny(String key) {
        return call(() -> { engine().deny(Collections.singletonList(key)); return engine().gate(); });
    }

    /** request: {key, entity, a, v}. */
    @JavascriptInterface public String correct(String request) {
        return call(() -> {
            Map<String, Object> r = Json.obj(request);
            engine().correct(String.valueOf(r.get("key")), String.valueOf(r.get("entity")), String.valueOf(r.get("a")), String.valueOf(r.get("v")));
            return engine().gate();
        });
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

    /**
     * The agent for one source: the model if it's loaded (and allowed), else the
     * pattern agent. The model's passages go into the trace as it reads them.
     */
    private Agent agentFor(String sourceName, ModelAgent.Progress progress) {
        Llama l = model.llama();
        if (l != null && model.useForExtraction())
            return new ModelAgent(l, Policy.SCHEMA_ORDER, ModelAgent.traced(engine(), sourceName, progress)).briefed(engine().notes(l.id()));
        return new Agent.PatternAgent();
    }

    private String sourceName(String sourceId) {
        for (Object o : engine().sources()) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (sourceId.equals(m.get("id"))) return String.valueOf(m.get("name"));
        }
        return sourceId;
    }

    /** Extract from one source. */
    @JavascriptInterface public String extract(final String sourceId) {
        return call(() -> {
            if (bulk().isRunning()) throw new Store.Rejected("a bulk run is going: pause it to extract one source by hand");
            final long[] id = new long[1];
            id[0] = job("extract", () -> {
                Agent agent = agentFor(sourceName(sourceId), new ModelAgent.Progress() {
                    @Override public void passage(int i, int n) { progress(id[0], "extract", Tx.m("passage", (long) i, "passages", (long) n)); }
                    @Override public void text(String piece) { }
                });
                Map<String, Object> r = engine().extract(sourceId, readable(), agent);
                r.put("source", sourceId);
                return r;
            });
            return id[0];
        });
    }

    // ------------------------------------------------------------ bulk extract

    /** request: {ids: [...], names: [...], skip: bool}. Extract all is the same call with every source. */
    @JavascriptInterface public String bulkStart(final String request) {
        return call(() -> {
            Map<String, Object> req = Json.obj(request);
            List<String> ids = new ArrayList<>(), names = new ArrayList<>();
            for (Object o : (List<?>) req.get("ids")) ids.add(String.valueOf(o));
            Object n = req.get("names");
            if (n instanceof List) for (Object o : (List<?>) n) names.add(String.valueOf(o));
            BulkRun b = bulk();
            if (!b.isRunning() && !"idle".equals(b.status().get("state")) && !b.isActive()) b.clear();
            b.start(ids, names, !Boolean.FALSE.equals(req.get("skip")), readable(), this::agentFor);
            return b.status();
        });
    }
    @JavascriptInterface public String bulkStatus() { return call(() -> bulk().status()); }
    @JavascriptInterface public String bulkPause() { return call(() -> { bulk().pause(); return bulk().status(); }); }
    @JavascriptInterface public String bulkResume() { return call(() -> { bulk().resume(readable(), this::agentFor); return bulk().status(); }); }
    @JavascriptInterface public String bulkCancel() { return call(() -> { bulk().cancel(); return bulk().status(); }); }
    @JavascriptInterface public String bulkClear() { return call(() -> { bulk().clear(); return bulk().status(); }); }

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
            if (bulk().isRunning()) throw new Store.Rejected("a bulk run is going: pause it before replacing the model");
            model.importFrom(cr, uri, fname, fsize, (done, total) ->
                    progress(id[0], "import", Tx.m("bytes", done, "total", total)));
            return loadAndNote();
        });
    }

    @JavascriptInterface public String loadModel() { return call(() -> job("load", this::loadAndNote)); }
    @JavascriptInterface public String unloadModel() {
        return call(() -> {
            if (bulk().isRunning() && model.useForExtraction()) throw new Store.Rejected("a bulk run is using the model: pause it first");
            model.unload();
            engine().noteWork("model", "Model unloaded", Tx.m("event", "unload"));
            return model.status();
        });
    }

    private Map<String, Object> loadAndNote() {
        Map<String, Object> s = model.load();
        Map<?, ?> info = (Map<?, ?>) s.get("info");
        engine().noteWork("model", "Model loaded: " + (info == null ? model.id() : info.get("desc")) + " · " + s.get("threads") + " threads",
                Tx.m("event", "load", "threads", s.get("threads")));
        engine().present(model.id());                                   // it goes before the arbiters before it does any work
        return s;
    }

    /** 0 = automatic. A loaded model is reloaded with the new count. */
    @JavascriptInterface public String setThreads(final int n) {
        return call(() -> {
            model.setThreads(n);
            if (!model.loaded()) return model.status();
            if (bulk().isRunning()) throw new Store.Rejected("saved; it applies once the bulk run is paused or finished and the model is reloaded");
            return job("load", () -> { model.unload(); return loadAndNote(); });
        });
    }
    @JavascriptInterface public String removeModel() {
        return call(() -> {
            if (bulk().isRunning()) throw new Store.Rejected("a bulk run is going: pause it first");
            model.remove();
            return model.status();
        });
    }
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
                String answer = l.generate(Ask.messages(history, q, facts, useMemory, think), 768, 0.6f, think, piece -> {
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
