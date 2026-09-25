package app.dilmun.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The append-only log. Every check that must hold no matter what the arbiter
 * code does lives here: identity of the transaction, who signed it, what that
 * signer may sign, the per-portal hash chain, forks, the directive lifecycle
 * and skill-version immutability. There is no update or delete.
 */
public final class Store {

    public static final class Rejected extends RuntimeException {
        public Rejected(String why) { super(why); }
    }

    public static final Set<String> STEWARD_KINDS = new HashSet<>(Arrays.asList(
            "genesis", "admit", "schema", "approve", "deny", "correct", "pause", "resume", "skill"));
    public static final Set<String> PORTAL_KINDS = new HashSet<>(Arrays.asList(
            "directive", "assert", "expire", "promote", "map", "retract", "dream", "enlist"));
    public static final Set<String> CLOSING_KINDS = new HashSet<>(Arrays.asList("assert", "expire"));

    private final LinkedHashMap<String, Map<String, Object>> txs = new LinkedHashMap<>();
    private final Map<String, String> roles = new HashMap<>();
    private String genesis;
    private final Map<String, Map<String, Object>> directives = new HashMap<>();
    private final Map<String, String> directiveClosed = new HashMap<>();
    private final Map<String, String> child = new HashMap<>();
    private final Map<String, Integer> depth = new HashMap<>();
    private final TreeMap<String, Map<String, Object>> proofs = new TreeMap<>();
    private final Set<String> skillVersions = new HashSet<>();

    public Store() {
        for (Map.Entry<String, List<Long>> e : Policy.DEFAULT_SKILLS_VERSIONS.entrySet())
            for (Long v : e.getValue()) skillVersions.add(e.getKey() + "@" + v);
    }

    // ------------------------------------------------------------ reads (copies)

    public List<Map<String, Object>> all() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : txs.values()) out.add(copy(t));
        return out;
    }

    public Map<String, Object> get(String id) {
        Map<String, Object> t = txs.get(id);
        return t == null ? null : copy(t);
    }

    public boolean contains(String id) { return txs.containsKey(id); }
    public int size() { return txs.size(); }
    public String genesisId() { return genesis; }
    public String role(String pub) { return roles.get(pub); }

    /** What replay reads: everything except a forked portal's transactions from the fork point on. */
    public List<Map<String, Object>> valid() {
        Map<String, Integer> cut = new HashMap<>();
        for (Map<String, Object> pr : proofs.values()) {
            String portal = (String) pr.get("portal"), prev = (String) pr.get("prev");
            Integer d = prev == null ? Integer.valueOf(0) : depth.get(prev);
            if (d != null) cut.put(portal, Math.min(cut.containsKey(portal) ? cut.get(portal) : d, d));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : txs.values()) {
            Integer c = cut.get(Tx.portal(t));
            if (c != null && depth.get(Tx.id(t)) > c) continue;
            out.add(copy(t));
        }
        return out;
    }

    /** The newest link of a portal's chain held here. A portal always writes after it. */
    public String tip(String portal) {
        String best = null;
        int bestDepth = -1;
        for (Map<String, Object> t : txs.values()) {
            if (!portal.equals(Tx.portal(t))) continue;
            int d = depth.get(Tx.id(t));
            if (d > bestDepth) { bestDepth = d; best = Tx.id(t); }
        }
        return best;
    }

    public boolean frozen(String portal) {
        for (Map<String, Object> pr : proofs.values()) if (portal.equals(pr.get("portal"))) return true;
        return false;
    }

    public List<Map<String, Object>> forkProofs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : proofs.values()) out.add(copy(p));
        return out;
    }

    // ------------------------------------------------------------ append

    @SuppressWarnings("unchecked")
    public boolean append(Map<String, Object> incoming) {
        Map<String, Object> tx = copy(incoming);
        Map<String, Object> h = Tx.header(tx);
        String id = Tx.id(tx), kind = Tx.kind(tx), signer = (String) h.get("signer");
        if (!Crypto.H(h).equals(id)) throw new Rejected("id does not match header");
        if (!Crypto.verify(signer, (String) tx.get("sig"), id)) throw new Rejected("bad signature");

        if (genesis == null) {
            if (!"genesis".equals(kind)) throw new Rejected("the log must start with a genesis transaction");
            Map<String, Object> p = Tx.payload(tx);
            Map<String, Object> r = (Map<String, Object>) p.get("roles");
            if (!signer.equals(p.get("steward")) || !"steward".equals(r.get(signer)))
                throw new Rejected("genesis must be signed by the steward it names");
        } else if ("genesis".equals(kind)) {
            if (id.equals(genesis)) return false;
            throw new Rejected("this log already has a genesis; the transaction belongs to another network");
        }

        String role = genesis == null ? "steward" : roles.get(signer);
        if (role == null) throw new Rejected("unknown signer");
        if (STEWARD_KINDS.contains(kind) && !"steward".equals(role))
            throw new Rejected("only the steward may sign " + kind);
        if (PORTAL_KINDS.contains(kind) && !"portal".equals(role))
            throw new Rejected("only arbiter code on a portal may sign " + kind);
        if (!STEWARD_KINDS.contains(kind) && !PORTAL_KINDS.contains(kind))
            throw new Rejected("unknown kind " + kind);
        if ("culture".equals(Tx.tier(tx)) && !"promote".equals(kind))
            throw new Rejected("only the promotion gate writes to culture");

        if (txs.containsKey(id)) return false;
        String prev = Tx.prev(tx), portal = Tx.portal(tx);
        if (prev != null && !txs.containsKey(prev)) throw new Rejected("missing parent in portal chain");
        if (prev != null && !portal.equals(Tx.portal(txs.get(prev)))) throw new Rejected("parent belongs to another portal");
        String slot = portal + "\u0000" + prev;
        if (child.containsKey(slot) && !child.get(slot).equals(id)) {
            Map<String, Object> other = txs.get(child.get(slot));
            addProof(Tx.m("a", headerOnly(other), "b", headerOnly(tx)));
            throw new Rejected("fork: " + portal + " signed two transactions after one parent");
        }

        checkPayload(tx);
        checkDirective(tx);
        if ("skill".equals(kind)) {
            Map<String, Object> p = Tx.payload(tx);
            String sv = p.get("name") + "@" + p.get("version");
            if (skillVersions.contains(sv)) throw new Rejected("a published skill version never changes; publish a new one");
            skillVersions.add(sv);
        }

        txs.put(id, tx);
        child.put(slot, id);
        depth.put(id, prev == null ? 1 : depth.get(prev) + 1);
        if ("genesis".equals(kind)) {
            genesis = id;
            Map<String, Object> r = (Map<String, Object>) Tx.payload(tx).get("roles");
            for (Map.Entry<String, Object> e : r.entrySet()) roles.put(e.getKey(), (String) e.getValue());
        } else if ("admit".equals(kind)) {
            Map<String, Object> p = Tx.payload(tx);
            roles.put((String) p.get("pub"), (String) p.get("role"));
        }
        return true;
    }

    private void checkPayload(Map<String, Object> tx) {
        Map<String, Object> p = Tx.payload(tx);
        if (p != null && Boolean.TRUE.equals(p.get("withheld"))) {
            if (!Tx.isEpisode(Tx.tier(tx))) throw new Rejected("only episode payloads may be withheld");
            return;
        }
        if (!Crypto.H(p).equals(Tx.header(tx).get("payload_hash"))) throw new Rejected("payload does not match header");
    }

    private void checkDirective(Map<String, Object> tx) {
        Map<String, Object> h = Tx.header(tx), p = Tx.payload(tx);
        String kind = Tx.kind(tx);
        if ("directive".equals(kind)) {
            directives.put((String) p.get("id"), Tx.m(
                    "portal", Tx.portal(tx),
                    "expires", p.get("expires"),
                    "budget", p.containsKey("budget") ? p.get("budget") : Long.valueOf(Policy.DEFAULT_BUDGET)));
            return;
        }
        if (!CLOSING_KINDS.contains(kind)) return;
        String did = (String) h.get("directive");
        Map<String, Object> d = directives.get(did);
        if (d == null || !Tx.portal(tx).equals(d.get("portal")))
            throw new Rejected("only the issuing portal can close a directive");
        if (directiveClosed.containsKey(did)) throw new Rejected("directive already closed");
        long wall = Tx.wall(tx);
        Number exp = (Number) d.get("expires");
        if ("assert".equals(kind)) {
            if (exp != null && wall >= exp.longValue()) throw new Rejected("result after the directive expired");
            if (p != null && !Boolean.TRUE.equals(p.get("withheld"))) {
                List<?> datoms = (List<?>) p.get("datoms");
                long facts = 0;
                if (datoms != null) for (Object o : datoms) if (!"name".equals(((Map<?, ?>) o).get("a"))) facts++;
                if (facts > ((Number) d.get("budget")).longValue()) throw new Rejected("result over budget");
            }
        } else if (exp == null || wall < exp.longValue()) {
            throw new Rejected("expiry before the directive's deadline");
        }
        directiveClosed.put(did, Tx.id(tx));
    }

    // ------------------------------------------------------------ forks

    private static Map<String, Object> headerOnly(Map<String, Object> t) {
        return Tx.m("id", t.get("id"), "header", t.get("header"), "sig", t.get("sig"));
    }

    /** Two transactions, both validly signed by one portal key, after the same parent. */
    @SuppressWarnings("unchecked")
    public boolean addProof(Map<String, Object> proof) {
        Map<String, Object> a = (Map<String, Object>) proof.get("a"), b = (Map<String, Object>) proof.get("b");
        for (Map<String, Object> t : Arrays.asList(a, b)) {
            Map<String, Object> h = (Map<String, Object>) t.get("header");
            if (!Crypto.H(h).equals(t.get("id")) || !Crypto.verify((String) h.get("signer"), (String) t.get("sig"), (String) t.get("id")))
                throw new Rejected("bad fork proof");
            if (roles.get(h.get("signer")) == null) throw new Rejected("bad fork proof");
        }
        Map<String, Object> ha = (Map<String, Object>) a.get("header"), hb = (Map<String, Object>) b.get("header");
        if (a.get("id").equals(b.get("id")) || !ha.get("portal").equals(hb.get("portal"))
                || !eq(ha.get("prev"), hb.get("prev")) || !ha.get("signer").equals(hb.get("signer")))
            throw new Rejected("bad fork proof");
        String key = Json.canon(Arrays.asList(ha.get("portal"), ha.get("prev")));
        if (proofs.containsKey(key)) return false;
        Map<String, Object> stored = copy(proof);
        stored.put("portal", ha.get("portal"));
        stored.put("prev", ha.get("prev"));
        proofs.put(key, stored);
        return true;
    }

    private static boolean eq(Object x, Object y) { return x == null ? y == null : x.equals(y); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> copy(Map<String, Object> m) {
        return (Map<String, Object>) Json.parse(Json.canon(m));
    }
}
