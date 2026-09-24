package app.dilmun.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Desktop preview of the app's screens against the real engine, with keys
 * and the log in memory. Not part of the APK. It mirrors the Android bridge,
 * including jobs and events (delivered by polling here).
 *
 *   java -cp <core classes> app.dilmun.core.DevServer app/src/main/assets 8765
 *   add -Ddilmun.llm=<libdilmun_llm.so> -Ddilmun.model=<model.gguf> to try the model;
 *   "Import model file" then loads that file. -Ddilmun.sources=<folder> reads that
 *   folder's files as the sources instead of the bundled samples.
 */
public final class DevServer {

    static final class DirSources implements Engine.Sources {
        final File dir;
        DirSources(File dir) { this.dir = dir; }
        @Override public List<Map<String, Object>> list() {
            List<Map<String, Object>> out = new ArrayList<>();
            File[] fs = dir.listFiles();
            if (fs == null) return out;
            java.util.Arrays.sort(fs);
            for (File f : fs) {
                String text = read("sample:" + f.getName());
                if (text != null) out.add(Tx.m("id", "sample:" + f.getName(), "name", "samples/" + f.getName(), "hash", Crypto.textHash(text)));
            }
            return out;
        }
        @Override public String read(String id) {
            try { return new String(Files.readAllBytes(new File(dir, id.substring(7)).toPath()), StandardCharsets.UTF_8); }
            catch (Exception e) { return null; }
        }
    }

    static Engine engine;
    static DirSources samples;
    static boolean added = false, useModel = true, imported = false;
    static volatile Llama llama;
    static String modelSha = "";
    static final List<Object> events = new ArrayList<>();
    static final ExecutorService work = Executors.newSingleThreadExecutor();
    static final AtomicLong jobs = new AtomicLong();
    static volatile boolean traceNudged = false;
    static BulkRun bulk;
    static int threadsSetting = 0;
    static String bulkSaved = null;

    static synchronized void emit(String name, Object payload) { events.add(java.util.Arrays.asList(name, payload)); }

    interface Body { Object run() throws Exception; }

    static long job(String kind, Body b) {
        long id = jobs.incrementAndGet();
        work.execute(() -> {
            try { emit("job", Tx.m("id", id, "kind", kind, "state", "done", "result", b.run())); }
            catch (Exception e) { emit("job", Tx.m("id", id, "kind", kind, "state", "error", "error", String.valueOf(e.getMessage()))); }
        });
        return id;
    }

    static String modelId() { return "model:" + new File(System.getProperty("dilmun.model", "none.gguf")).getName().replace(".gguf", "") + ":" + (modelSha.isEmpty() ? "000000000000" : modelSha.substring(0, 12)); }

    static Map<String, Object> modelStatus() {
        File f = new File(System.getProperty("dilmun.model", "/nonexistent"));
        return Tx.m("file", imported && f.isFile() ? Tx.m("name", f.getName().replace(".gguf", ""), "bytes", f.length(), "sha", modelSha, "id", modelId()) : null,
                "loaded", llama != null, "loading", false, "error", null, "useForExtraction", useModel,
                "info", llama == null ? null : llama.info(), "threads", (long) threads(),
                "threadsSetting", (long) threadsSetting, "threadsAuto", (long) Llama.defaultThreads(),
                "cores", (long) Runtime.getRuntime().availableProcessors());
    }

    static int threads() { return threadsSetting > 0 ? threadsSetting : 2; }

    static Object load() {
        if (llama == null) {
            llama = Llama.load(System.getProperty("dilmun.model"), modelId(), 2048, threads());
            engine.noteWork("model", "Model loaded: " + llama.info().get("desc") + " · " + threads() + " threads", Tx.m("event", "load"));
        }
        return modelStatus();
    }

    static Agent agentFor(String name, ModelAgent.Progress progress) {
        return llama != null && useModel
                ? new ModelAgent(llama, Policy.SCHEMA_ORDER, ModelAgent.traced(engine, name, progress))
                : new Agent.PatternAgent();
    }

    static String nameOf(String id) {
        for (Object o : engine.sources()) { Map<?, ?> m = (Map<?, ?>) o; if (id.equals(m.get("id"))) return String.valueOf(m.get("name")); }
        return id;
    }

    public static void main(String[] args) throws Exception {
        final File assets = new File(args.length > 0 ? args[0] : "app/src/main/assets");
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8765;
        if (System.getProperty("dilmun.llm") != null) { System.load(System.getProperty("dilmun.llm")); Llama.init(null); }
        engine = Engine.open(new CoreTest.MemBackend(), new Crypto.SoftSigner(), new Crypto.SoftSigner(),
                Engine.SYSTEM_CLOCK, new Agent.PatternAgent());
        samples = new DirSources(new File(System.getProperty("dilmun.sources", new File(assets, "samples").getPath())));
        engine.onTrace(() -> {
            if (traceNudged) return;
            traceNudged = true;
            emit("trace", null);
        });
        bulk = new BulkRun(engine, work::execute, st -> emit("bulk", st), sv -> bulkSaved = sv == null ? null : Json.canon(sv));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/events", ex -> {
            String out;
            synchronized (DevServer.class) { out = Json.canon(new ArrayList<>(events)); events.clear(); }
            send(ex, 200, "application/json", out.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/bridge/", ex -> {
            String name = ex.getRequestURI().getPath().substring("/bridge/".length());
            List<?> a = (List<?>) Json.parse(new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8));
            String out;
            try {
                out = Json.canon(Tx.m("ok", dispatch(name, a)));
            } catch (RuntimeException e) {
                out = Json.canon(Tx.m("error", e.getMessage()));
            }
            send(ex, 200, "application/json", out.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            File f = new File(assets, path.substring(1));
            if (!f.getCanonicalPath().startsWith(assets.getCanonicalPath()) || !f.isFile()) { send(ex, 404, "text/plain", new byte[0]); return; }
            byte[] body = Files.readAllBytes(f.toPath());
            if (path.equals("/index.html")) {
                String html = new String(body, StandardCharsets.UTF_8).replace("<script src=\"app.js\"></script>",
                        "<script>" + SHIM + "</script>\n<script src=\"app.js\"></script>");
                body = html.getBytes(StandardCharsets.UTF_8);
            }
            send(ex, 200, type(path), body);
        });
        server.start();
        System.out.println("Dilmun dev preview on http://127.0.0.1:" + port + "/");
    }

    @SuppressWarnings("unchecked")
    static Object dispatch(String name, List<?> a) {
        switch (name) {
            case "summary": {
                Map<String, Object> s = new TreeMap<>(engine.summary());
                s.put("folder", null); s.put("samples", added);
                Map<String, Object> ms = modelStatus();
                Map<?, ?> file = (Map<?, ?>) ms.get("file");
                s.put("model", Tx.m("name", file == null ? null : file.get("name"), "id", file == null ? null : file.get("id"),
                        "loaded", ms.get("loaded"), "extract", useModel));
                s.put("agent", llama != null && useModel ? modelId() : new Agent.PatternAgent().id());
                return s;
            }
            case "sources": return engine.sources();
            case "directives": return engine.directives();
            case "results": return engine.results(((Number) a.get(0)).intValue());
            case "memory": return engine.memory((String) a.get(0));
            case "held": return engine.held();
            case "log": return engine.log(((Number) a.get(0)).intValue(), ((Number) a.get(1)).intValue());
            case "tx": return Json.parse(engine.tx((String) a.get(0)));
            case "verify": return engine.verify();
            case "trace": traceNudged = false; return engine.trace(((Number) a.get(0)).longValue());
            case "extracted": {
                List<Object> out = new ArrayList<>();
                for (Object o : engine.sources()) { String id = (String) ((Map<?, ?>) o).get("id"); if (engine.extracted(id)) out.add(id); }
                return out;
            }
            case "bulkStart": {
                Map<String, Object> req = Json.obj((String) a.get(0));
                List<String> ids = new ArrayList<>(), names = new ArrayList<>();
                for (Object o : (List<?>) req.get("ids")) ids.add(String.valueOf(o));
                if (req.get("names") instanceof List) for (Object o : (List<?>) req.get("names")) names.add(String.valueOf(o));
                if (!bulk.isActive() && !"idle".equals(bulk.status().get("state"))) bulk.clear();
                bulk.start(ids, names, !Boolean.FALSE.equals(req.get("skip")), samples, DevServer::agentFor);
                return bulk.status();
            }
            case "bulkStatus": return bulk.status();
            case "bulkPause": bulk.pause(); return bulk.status();
            case "bulkResume": bulk.resume(samples, DevServer::agentFor); return bulk.status();
            case "bulkCancel": bulk.cancel(); return bulk.status();
            case "bulkClear": bulk.clear(); return bulk.status();
            case "setThreads": {
                threadsSetting = ((Number) a.get(0)).intValue();
                if (llama == null) return modelStatus();
                Llama l = llama; llama = null; l.close();
                return job("load", DevServer::load);
            }
            case "rescan": return engine.scan(samples);
            case "useSamples": added = true; return engine.scan(samples);
            case "pickFolder": added = true; return job("scan", () -> engine.scan(samples));
            case "gate": return engine.gate();
            case "reconcile": return engine.reconcile();
            case "pause": engine.pause(); return true;
            case "resume": engine.resume(); return true;
            case "approve": engine.approve(Collections.singletonList((String) a.get(0))); return engine.gate();
            case "modelStatus": return modelStatus();
            case "setUseModel": useModel = Boolean.TRUE.equals(a.get(0)); return modelStatus();
            case "unloadModel": { Llama l = llama; llama = null; if (l != null) { l.close(); engine.noteWork("model", "Model unloaded", Tx.m("event", "unload")); } return modelStatus(); }
            case "removeModel": { Llama l = llama; llama = null; if (l != null) l.close(); imported = false; return modelStatus(); }
            case "loadModel": return job("load", DevServer::load);
            case "stop": { Llama l = llama; if (l != null) l.stop(); return true; }
            case "openLink": return null;
            case "importModel": {
                if (System.getProperty("dilmun.model") == null) throw new Store.Rejected("start the preview with -Ddilmun.model=<file.gguf>");
                final long[] id = new long[1];
                id[0] = job("import", () -> {
                    File f = new File(System.getProperty("dilmun.model"));
                    byte[] all = Files.readAllBytes(f.toPath());
                    for (int i = 1; i <= 4; i++) {
                        emit("job", Tx.m("id", id[0], "kind", "import", "state", "progress", "bytes", (long) all.length * i / 4, "total", (long) all.length));
                        Thread.sleep(150);
                    }
                    modelSha = Crypto.sha256(all);
                    imported = true;
                    return load();
                });
                return null;
            }
            case "extract": {
                final String sid = (String) a.get(0);
                final long[] id = new long[1];
                id[0] = job("extract", () -> {
                    Agent agent = agentFor(nameOf(sid), new ModelAgent.Progress() {
                        @Override public void passage(int i, int n) { emit("job", Tx.m("id", id[0], "kind", "extract", "state", "progress", "passage", (long) i, "passages", (long) n)); }
                        @Override public void text(String piece) { }
                    });
                    Map<String, Object> r = engine.extract(sid, samples, agent);
                    r.put("source", sid);
                    return r;
                });
                return id[0];
            }
            case "ask": {
                final Map<String, Object> req = Json.obj((String) a.get(0));
                final Llama l = llama;
                if (l == null) throw new Store.Rejected("load the model first");
                final long[] id = new long[1];
                id[0] = job("ask", () -> {
                    String q = String.valueOf(req.get("question"));
                    boolean mem = !Boolean.FALSE.equals(req.get("memory"));
                    List<Object> facts = mem ? engine.recall(q, 8) : new ArrayList<Object>();
                    List<String[]> hist = new ArrayList<>();
                    for (Object o : (List<Object>) req.get("history")) { List<?> t = (List<?>) o; hist.add(new String[]{(String) t.get(0), (String) t.get(1)}); }
                    emit("job", Tx.m("id", id[0], "kind", "ask", "state", "progress", "facts", facts));
                    String answer = l.generate(Ask.messages(hist, q, facts, mem), 96, 0.6f, Boolean.TRUE.equals(req.get("think")),
                            piece -> { emit("token", Tx.m("id", id[0], "text", new String(piece, StandardCharsets.UTF_8))); return true; });
                    return Tx.m("text", answer, "facts", facts, "stats", l.stats(), "model", l.id());
                });
                return id[0];
            }
            default: throw new Store.Rejected("unknown call " + name);
        }
    }

    static final String SHIM =
            "window.Dilmun = new Proxy({}, { get: function (t, name) { return function () {" +
            "  var x = new XMLHttpRequest(); x.open('POST', '/bridge/' + name, false);" +
            "  x.send(JSON.stringify(Array.prototype.slice.call(arguments)));" +
            "  return x.responseText; }; } });" +
            "setInterval(function () { var x = new XMLHttpRequest(); x.open('GET', '/events', false); x.send();" +
            "  JSON.parse(x.responseText).forEach(function (e) { window.dilmunEvent && window.dilmunEvent(e[0], e[1]); }); }, 120);";

    static String type(String p) {
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        return "text/plain; charset=utf-8";
    }

    static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        OutputStream o = ex.getResponseBody();
        o.write(body);
        o.close();
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
