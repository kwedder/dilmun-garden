package app.dilmun.portal;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.dilmun.core.Agent;
import app.dilmun.core.Engine;
import app.dilmun.core.Json;
import app.dilmun.core.Store;
import app.dilmun.core.Tx;

/**
 * What the screens can ask for. The screens are HTML in a WebView; every call
 * comes here and goes to the engine, and every answer is canonical JSON:
 * {"ok": value} or {"error": "what went wrong"}. The screens never write to
 * the log themselves.
 */
final class Bridge {
    private final MainActivity activity;
    private final WebView web;
    private final SharedPreferences prefs;
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private Engine engine;
    private TreeSources folder;
    private AssetSources samples;

    Bridge(MainActivity activity, WebView web) {
        this.activity = activity;
        this.web = web;
        this.prefs = activity.getSharedPreferences("dilmun", Context.MODE_PRIVATE);
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

    private interface Call { Object run(); }

    private static String call(Call c) {
        try {
            return Json.canon(Tx.m("ok", c.run()));
        } catch (Store.Rejected e) {
            return Json.canon(Tx.m("error", e.getMessage()));
        } catch (RuntimeException e) {
            return Json.canon(Tx.m("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private void emit(final String event, final String json) {
        web.post(new Runnable() {
            @Override public void run() {
                web.evaluateJavascript("window.dilmunEvent && window.dilmunEvent(" + Json.canon(event) + "," + json + ")", null);
            }
        });
    }

    // ------------------------------------------------------------ reads

    @JavascriptInterface public String summary() {
        return call(() -> {
            Map<String, Object> s = new TreeMap<>(engine().summary());
            s.put("folder", folder == null ? null : folder.label());
            s.put("samples", samples != null);
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

    // ------------------------------------------------------------ actions

    @JavascriptInterface public String rescan() { return call(() -> engine().scan(readable())); }
    @JavascriptInterface public String extract(String sourceId) { return call(() -> engine().extract(sourceId, readable())); }
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
        activity.runOnUiThread(new Runnable() {
            @Override public void run() { activity.pickFolder(); }
        });
    }

    /** Called by the activity when the folder picker returns. Scans in the background. */
    void onTreePicked(Uri tree) {
        prefs.edit().putString("tree", tree.toString()).apply();
        folder = new TreeSources(activity.getContentResolver(), tree);
        background.execute(new Runnable() {
            @Override public void run() { emit("scanned", call(() -> engine().scan(readable()))); }
        });
    }
}
