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

/**
 * Desktop preview of the app's screens against the real engine, with keys in
 * memory and the log in memory. Not part of the APK.
 *
 *   java -cp <core classes> app.dilmun.core.DevServer app/src/main/assets 8765
 *   then open http://localhost:8765/
 */
public final class DevServer {

    static final class DirSources implements Engine.Sources {
        final File dir;
        DirSources(File dir) { this.dir = dir; }
        @Override public List<Map<String, Object>> list() {
            List<Map<String, Object>> out = new ArrayList<>();
            File[] fs = dir.listFiles();
            if (fs == null) return out;
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

    public static void main(String[] args) throws Exception {
        final File assets = new File(args.length > 0 ? args[0] : "app/src/main/assets");
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8765;
        final Engine engine = Engine.open(new CoreTest.MemBackend(), new Crypto.SoftSigner(), new Crypto.SoftSigner(),
                Engine.SYSTEM_CLOCK, new Agent.PatternAgent());
        final DirSources samples = new DirSources(new File(assets, "samples"));
        final boolean[] added = {false};

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/bridge/", ex -> {
            String name = ex.getRequestURI().getPath().substring("/bridge/".length());
            List<?> a = (List<?>) Json.parse(new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8));
            String out;
            try {
                Object r;
                switch (name) {
                    case "summary": {
                        Map<String, Object> s = new TreeMap<>(engine.summary());
                        s.put("folder", null); s.put("samples", added[0]); r = s; break;
                    }
                    case "sources": r = engine.sources(); break;
                    case "directives": r = engine.directives(); break;
                    case "results": r = engine.results(((Number) a.get(0)).intValue()); break;
                    case "memory": r = engine.memory((String) a.get(0)); break;
                    case "held": r = engine.held(); break;
                    case "log": r = engine.log(((Number) a.get(0)).intValue(), ((Number) a.get(1)).intValue()); break;
                    case "tx": r = Json.parse(engine.tx((String) a.get(0))); break;
                    case "verify": r = engine.verify(); break;
                    case "rescan": r = engine.scan(samples); break;
                    case "useSamples": case "pickFolder": added[0] = true; r = engine.scan(samples); break;
                    case "extract": r = engine.extract((String) a.get(0), samples); break;
                    case "gate": r = engine.gate(); break;
                    case "reconcile": r = engine.reconcile(); break;
                    case "pause": engine.pause(); r = true; break;
                    case "resume": engine.resume(); r = true; break;
                    case "approve": engine.approve(Collections.singletonList((String) a.get(0))); r = engine.gate(); break;
                    default: throw new Store.Rejected("unknown call " + name);
                }
                out = Json.canon(Tx.m("ok", r));
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

    static final String SHIM =
            "window.Dilmun = new Proxy({}, { get: function (t, name) { return function () {" +
            "  var x = new XMLHttpRequest(); x.open('POST', '/bridge/' + name, false);" +
            "  x.send(JSON.stringify(Array.prototype.slice.call(arguments)));" +
            "  if (name === 'pickFolder') { setTimeout(function () { window.dilmunEvent && window.dilmunEvent('scanned', JSON.parse(x.responseText)); }, 0); return; }" +
            "  return x.responseText; }; } });";

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
