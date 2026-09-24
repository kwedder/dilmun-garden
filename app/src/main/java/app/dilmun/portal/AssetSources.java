package app.dilmun.portal;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.dilmun.core.Crypto;
import app.dilmun.core.Engine;
import app.dilmun.core.Tx;

/** The sample texts bundled with the app, so the whole path can be tried before picking a folder. */
final class AssetSources implements Engine.Sources {
    private final AssetManager assets;

    AssetSources(AssetManager assets) { this.assets = assets; }

    @Override public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            String[] names = assets.list("samples");
            if (names == null) return out;
            for (String n : names) {
                String text = read("sample:" + n);
                if (text != null) out.add(Tx.m("id", "sample:" + n, "name", "samples/" + n, "hash", Crypto.textHash(text)));
            }
        } catch (Exception e) {
            // no samples
        }
        return out;
    }

    @Override public String read(String id) {
        if (!id.startsWith("sample:")) return null;
        try {
            InputStream in = assets.open("samples/" + id.substring("sample:".length()));
            try {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
