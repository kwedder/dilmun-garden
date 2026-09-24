package app.dilmun.portal;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.dilmun.core.Crypto;
import app.dilmun.core.Engine;
import app.dilmun.core.Json;
import app.dilmun.core.Tx;

/**
 * The log on disk. Transactions are stored as their canonical JSON, in arrival
 * order, and never updated. State is rebuilt from them at every startup; the
 * datom tables and indexes described in ARCHITECTURE.md come in a later build.
 */
final class SqliteBackend extends SQLiteOpenHelper implements Engine.LogBackend {

    SqliteBackend(Context context) {
        super(context, "dilmun.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tx (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, "
                + "kind TEXT NOT NULL, portal TEXT NOT NULL, json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE proof (k TEXT PRIMARY KEY, json TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // version 1 only
    }

    @Override public List<Map<String, Object>> loadTxs() {
        return load("SELECT json FROM tx ORDER BY seq");
    }

    @Override public List<Map<String, Object>> loadProofs() {
        return load("SELECT json FROM proof ORDER BY k");
    }

    private List<Map<String, Object>> load(String sql) {
        List<Map<String, Object>> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(sql, null);
        try {
            while (c.moveToNext()) out.add(Json.obj(c.getString(0)));
        } finally {
            c.close();
        }
        return out;
    }

    @Override public void putTx(Map<String, Object> tx) {
        ContentValues v = new ContentValues();
        v.put("id", Tx.id(tx));
        v.put("kind", Tx.kind(tx));
        v.put("portal", Tx.portal(tx));
        v.put("json", Json.canon(tx));
        getWritableDatabase().insertWithOnConflict("tx", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    @Override public void putProof(Map<String, Object> proof) {
        ContentValues v = new ContentValues();
        v.put("k", Crypto.H(proof));
        v.put("json", Json.canon(proof));
        getWritableDatabase().insertWithOnConflict("proof", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }
}
