package app.dilmun.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The on-device model as an agent. It reads the directive's source a passage
 * at a time and proposes facts, each with a quote it claims is in the text.
 * It proposes only: the arbiters check every quote against the file by exact
 * match, and refuse the facts whose quote isn't there.
 *
 * The proposal records the model's raw output for every passage, so the
 * result can always be explained without running the model again.
 */
public final class ModelAgent implements Agent {

    /** Reports progress while the model works. */
    public interface Progress {
        void passage(int index, int count);
        void text(String piece);
        /** After each passage: facts the model proposed, and how many of their quotes are really in the text. */
        default void passageDone(int index, int count, int proposed, int quoted) { }
    }

    /**
     * Progress that also goes into the engine's trace, so the map and the
     * activity card show each passage as the model reads it. {@code also} (may
     * be null) gets every call too.
     */
    public static Progress traced(final Engine engine, final String source, final Progress also) {
        return new Progress() {
            @Override public void passage(int i, int n) {
                engine.noteWork("passage", "Model reading " + source + " · passage " + i + " of " + n,
                        Tx.m("i", (long) i, "n", (long) n, "source", source));
                if (also != null) also.passage(i, n);
            }
            @Override public void text(String piece) { if (also != null) also.text(piece); }
            @Override public void passageDone(int i, int n, int proposed, int quoted) {
                String q = proposed == 0 ? "no facts" : proposed + (proposed == 1 ? " fact" : " facts") + " proposed, "
                        + quoted + (quoted == 1 ? " quote" : " quotes") + " found in the text";
                engine.noteWork("passage-done", "Passage " + i + " of " + n + ": " + q,
                        Tx.m("i", (long) i, "n", (long) n, "proposed", (long) proposed, "quoted", (long) quoted));
                if (also != null) also.passageDone(i, n, proposed, quoted);
            }
        };
    }

    static final int PASSAGE_CHARS = 2400;
    static final int MAX_TOKENS = 512;

    private final Llm llm;
    private final List<String> attributes;
    private final Progress progress;

    public ModelAgent(Llm llm, List<String> attributes, Progress progress) {
        this.llm = llm;
        this.attributes = new ArrayList<>();
        for (String a : attributes) if (!"name".equals(a)) this.attributes.add(a);
        this.progress = progress;
    }

    @Override public String id() { return llm.id(); }

    String systemPrompt() {
        return "You read a passage and list the facts it states.\n"
                + "Write one fact per line, in exactly this form:\n"
                + "entity | attribute | value | quote\n"
                + "Use only these attributes: " + String.join(", ", attributes) + ".\n"
                + "The quote must be copied word for word from the passage: the shortest phrase that states the fact.\n"
                + "Write nothing else. If the passage states none of these facts, write NONE.\n\n"
                + "Example passage: Ibuprofen, an NSAID, is used to treat pain.\n"
                + "Example output:\n"
                + "ibuprofen | is_a | NSAID | Ibuprofen, an NSAID\n"
                + "ibuprofen | treats | pain | is used to treat pain";
    }

    @Override public Map<String, Object> propose(Map<String, Object> directive, String text) {
        List<int[]> passages = passages(text, PASSAGE_CHARS);
        List<Object> facts = new ArrayList<>(), raw = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int p = 0; p < passages.size(); p++) {
            int from = passages.get(p)[0], to = passages.get(p)[1];
            if (progress != null) progress.passage(p + 1, passages.size());
            List<String[]> msgs = new ArrayList<>();
            msgs.add(new String[]{"system", systemPrompt()});
            msgs.add(new String[]{"user", "Passage:\n" + text.substring(from, to)});
            String out = llm.generate(msgs, MAX_TOKENS, 0f, false, progress == null ? null : piece -> {
                progress.text(new String(piece, java.nio.charset.StandardCharsets.UTF_8));
                return true;
            });
            raw.add(Tx.m("from", (long) from, "to", (long) to, "output", out));
            int proposed = 0, quoted = 0;
            for (Map<String, Object> f : parse(out, text, from, to)) {
                String key = f.get("ident") + "|" + f.get("a") + "|" + String.valueOf(f.get("v")).toLowerCase(Locale.ROOT);
                if (!seen.add(key)) continue;
                facts.add(f);
                proposed++;
                if (((Number) ((Map<?, ?>) f.get("quote")).get("start")).longValue() >= 0) quoted++;
            }
            if (progress != null) progress.passageDone(p + 1, passages.size(), proposed, quoted);
        }
        return Tx.m("tools", new ArrayList<Object>(Arrays.asList("read_source")), "facts", facts,
                "raw", raw, "model", llm.id());
    }

    /** Splits text into passages at paragraph or sentence breaks, each at most max chars. */
    static List<int[]> passages(String text, int max) {
        List<int[]> out = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + max);
            if (end < text.length()) {
                int cut = text.lastIndexOf("\n\n", end);
                if (cut <= start + max / 3) cut = text.lastIndexOf(". ", end);
                if (cut <= start + max / 3) cut = text.lastIndexOf('\n', end);
                if (cut > start + max / 3) end = cut + 1;
            }
            if (text.substring(start, end).trim().length() > 0) out.add(new int[]{start, end});
            start = end;
        }
        return out;
    }

    /** Reads the model's lines. A quote the model invented gets offsets -1, and the arbiters refuse it. */
    static List<Map<String, Object>> parse(String out, String text, int from, int to) {
        List<Map<String, Object>> facts = new ArrayList<>();
        for (String line0 : out.split("\n")) {
            String line = line0.trim();
            if (line.startsWith("- ") || line.startsWith("* ")) line = line.substring(2).trim();
            if (line.isEmpty() || line.equalsIgnoreCase("NONE")) continue;
            String[] parts = line.split("\\|");
            if (parts.length < 4) continue;
            String e = parts[0].trim(), a = parts[1].trim().toLowerCase(Locale.ROOT).replace(' ', '_'), v = parts[2].trim();
            StringBuilder qb = new StringBuilder(parts[3]);
            for (int i = 4; i < parts.length; i++) qb.append('|').append(parts[i]);
            String quote = unquote(qb.toString().trim());
            if (e.isEmpty() || a.isEmpty() || v.isEmpty() || quote.isEmpty()) continue;
            int at = text.indexOf(quote, from);
            if (at < 0 || at + quote.length() > to) at = text.indexOf(quote);
            long start = at < 0 ? -1 : at, end = at < 0 ? -1 : at + quote.length();
            facts.add(Tx.m("ident", Arrays.asList("name", e), "a", a, "v", v, "nu", 700L,
                    "quote", Tx.m("start", start, "end", end, "text", quote)));
        }
        return facts;
    }

    private static String unquote(String q) {
        String[][] pairs = {{"\"", "\""}, {"“", "”"}, {"'", "'"}, {"‘", "’"}};
        for (String[] p : pairs)
            if (q.length() >= 2 && q.startsWith(p[0]) && q.endsWith(p[1])) return q.substring(p[0].length(), q.length() - p[1].length()).trim();
        return q;
    }
}
