package app.dilmun.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A transaction is a plain map: {id, header, sig, payload}. The id is the hash
 * of the header, the header commits to the payload by hash, and the signature
 * covers the id. Provenance lives in the header, once per transaction.
 */
public final class Tx {
    private Tx() {}

    public static final String STEWARD = "steward";

    public static Map<String, Object> make(Crypto.Signer key, String portal, String prev, long[] hlc,
                                           String kind, String tier, Map<String, Object> payload,
                                           Map<String, Object> meta) {
        TreeMap<String, Object> h = new TreeMap<>();
        if (meta != null) h.putAll(meta);
        h.put("portal", portal);
        h.put("prev", prev);
        h.put("hlc", Arrays.asList(hlc[0], hlc[1]));
        h.put("kind", kind);
        h.put("tier", tier);
        h.put("payload_hash", Crypto.H(payload));
        h.put("signer", key.pub());
        String id = Crypto.H(h);
        TreeMap<String, Object> tx = new TreeMap<>();
        tx.put("id", id);
        tx.put("header", h);
        tx.put("sig", key.sign(id));
        tx.put("payload", payload);
        return tx;
    }

    public static String id(Map<String, Object> tx) { return (String) tx.get("id"); }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> header(Map<String, Object> tx) { return (Map<String, Object>) tx.get("header"); }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> payload(Map<String, Object> tx) { return (Map<String, Object>) tx.get("payload"); }

    public static String kind(Map<String, Object> tx) { return (String) header(tx).get("kind"); }
    public static String tier(Map<String, Object> tx) { return (String) header(tx).get("tier"); }
    public static String portal(Map<String, Object> tx) { return (String) header(tx).get("portal"); }
    public static String prev(Map<String, Object> tx) { return (String) header(tx).get("prev"); }

    @SuppressWarnings("unchecked")
    public static long wall(Map<String, Object> tx) { return ((Number) ((List<Object>) header(tx).get("hlc")).get(0)).longValue(); }

    @SuppressWarnings("unchecked")
    public static long counter(Map<String, Object> tx) { return ((Number) ((List<Object>) header(tx).get("hlc")).get(1)).longValue(); }

    public static boolean isEpisode(String tier) { return tier != null && tier.startsWith("episode:"); }

    /** Replay order: (hlc, portal, id). The same on every portal. */
    public static final Comparator<Map<String, Object>> REPLAY = (a, b) -> {
        int c = Long.compare(wall(a), wall(b));
        if (c != 0) return c;
        c = Long.compare(counter(a), counter(b));
        if (c != 0) return c;
        c = portal(a).compareTo(portal(b));
        if (c != 0) return c;
        return id(a).compareTo(id(b));
    };

    /** Load order: steward transactions first, so trust and redactions are known on arrival; then replay order. */
    public static final Comparator<Map<String, Object>> LOAD = (a, b) -> {
        int sa = STEWARD.equals(portal(a)) ? 0 : 1, sb = STEWARD.equals(portal(b)) ? 0 : 1;
        if (sa != sb) return Integer.compare(sa, sb);
        return REPLAY.compare(a, b);
    };

    public static List<Map<String, Object>> sorted(List<Map<String, Object>> txs, Comparator<Map<String, Object>> order) {
        List<Map<String, Object>> out = new ArrayList<>(txs);
        Collections.sort(out, order);
        return out;
    }

    /** A small helper for building maps inline: M.of("a", 1, "b", "x"). */
    public static TreeMap<String, Object> m(Object... kv) {
        TreeMap<String, Object> out = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }
}
