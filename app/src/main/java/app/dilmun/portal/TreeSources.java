package app.dilmun.portal;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.dilmun.core.Crypto;
import app.dilmun.core.Engine;
import app.dilmun.core.Tx;

/**
 * Text files in a folder the user picked with the system folder picker. The
 * app gets read access to that folder only; nothing else on the device.
 */
final class TreeSources implements Engine.Sources {
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final int MAX_DEPTH = 6;

    private final ContentResolver cr;
    private final Uri tree;
    private final Map<String, String> docIds = new HashMap<>();

    TreeSources(ContentResolver cr, Uri tree) {
        this.cr = cr;
        this.tree = tree;
    }

    String label() {
        String seg = tree.getLastPathSegment();
        if (seg == null) return "Folder";
        int colon = seg.indexOf(':');
        String path = colon >= 0 ? seg.substring(colon + 1) : seg;
        return path.isEmpty() ? "Device storage" : path;
    }

    @Override public synchronized List<Map<String, Object>> list() {
        docIds.clear();
        List<Map<String, Object>> out = new ArrayList<>();
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0, out);
        return out;
    }

    private void walk(String docId, String prefix, int depth, List<Map<String, Object>> out) {
        if (depth > MAX_DEPTH) return;
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor c = cr.query(kids, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
        if (c == null) return;
        try {
            while (c.moveToNext()) {
                String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
                long size = c.isNull(3) ? 0 : c.getLong(3);
                if (name == null || name.startsWith(".")) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walk(id, prefix + name + "/", depth + 1, out);
                } else if (isText(name, mime) && size <= MAX_BYTES) {
                    String text = readDoc(id);
                    if (text == null) continue;
                    String sid = "src:" + Crypto.sha256(id.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
                    docIds.put(sid, id);
                    out.add(Tx.m("id", sid, "name", prefix + name, "hash", Crypto.textHash(text)));
                }
            }
        } finally {
            c.close();
        }
    }

    private static boolean isText(String name, String mime) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".markdown") || n.endsWith(".csv")
                || (mime != null && mime.startsWith("text/"));
    }

    @Override public synchronized String read(String id) {
        String doc = docIds.get(id);
        if (doc == null) { list(); doc = docIds.get(id); }
        return doc == null ? null : readDoc(doc);
    }

    private String readDoc(String docId) {
        Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        try {
            InputStream in = cr.openInputStream(u);
            if (in == null) return null;
            try {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) {
                    buf.write(b, 0, n);
                    if (buf.size() > MAX_BYTES) return null;
                }
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
