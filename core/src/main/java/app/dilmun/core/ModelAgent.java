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
 * at a time, as numbered sentences, and proposes facts, each naming the
 * sentence that states it. That sentence, cut from the file by the arbiters'
 * own split, is the fact's quote: the model never copies text, so each fact
 * costs it a few tokens and one pass collects more of them. It proposes only:
 * the arbiters check every fact against its sentence by rule (Grounding).
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

    /*
     * The prompt names no example concepts in its rules: a 1B model copies the
     * words it's given, and a real run put "biological anthropology" (from an
     * earlier rule's wording) at the head of facts in texts that never mention
     * it. The one example is kept far from any likely subject, and a fact
     * copied from it is refused anyway, since its words aren't in the sentence.
     */
    String systemPrompt() {
        return "You read numbered sentences and list every fact they state. Go through the sentences in order, "
                + "and write as many facts as each one states.\n"
                + "One fact per line, in exactly this form:\n"
                + "sentence number | entity | attribute | value\n"
                + "Use only these attributes: " + String.join(", ", attributes) + ".\n"
                + "Entity and value are short names taken from that sentence, words joined by _. "
                + "Name the whole thing, not one word of it, and leave out words that only praise or grade it. "
                + "Entity and value are never the same.\n"
                + "Use is_a only where the sentence says the entity is a value.\n"
                + "Skip opinions, questions and instructions to the reader. Write nothing else. If no sentence states a fact, write NONE.\n\n"
                + "Example sentences:\n"
                + "[1] The kestrel, a small falcon, hunts voles in Norway.\n"
                + "Example output:\n"
                + "1 | kestrel | is_a | falcon\n"
                + "1 | kestrel | located_in | Norway";
    }

    /**
     * The schema attribute a model meant: its own spelling if it's in the schema,
     * else the one attribute within two edits of it, three for a long name ("defines_as" → defined_as,
     * "location_in" → located_in). Otherwise as written, and the arbiters refuse it.
     */
    String attribute(String a) {
        if (attributes.contains(a)) return a;
        String best = null;
        int bestD = a.length() >= 9 ? 4 : 3;                          // within 3 edits for longer names, 2 for short
        boolean tie = false;
        for (String x : attributes) {
            int d = edits(a, x);
            if (d < bestD) { bestD = d; best = x; tie = false; } else if (d == bestD) tie = true;
        }
        return best != null && !tie ? best : a;
    }

    static int edits(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    @Override public Map<String, Object> propose(Map<String, Object> directive, String text) {
        List<int[]> passages = passages(text, PASSAGE_CHARS);
        List<Object> facts = new ArrayList<>(), raw = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int p = 0; p < passages.size(); p++) {
            int from = passages.get(p)[0], to = passages.get(p)[1];
            if (progress != null) progress.passage(p + 1, passages.size());
            List<int[]> sents = sentences(text, from, to);
            StringBuilder user = new StringBuilder("Sentences:\n");
            for (int i = 0; i < sents.size(); i++)
                user.append('[').append(i + 1).append("] ").append(text.substring(sents.get(i)[0], sents.get(i)[1]).replace('\n', ' ')).append('\n');
            List<String[]> msgs = new ArrayList<>();
            msgs.add(new String[]{"system", systemPrompt()});
            msgs.add(new String[]{"user", user.toString()});
            final LineWatch watch = new LineWatch();
            String out = llm.generate(msgs, MAX_TOKENS, 0f, false, piece -> {
                String t = new String(piece, java.nio.charset.StandardCharsets.UTF_8);
                if (progress != null) progress.text(t);
                return watch.more(t);
            });
            raw.add(Tx.m("from", (long) from, "to", (long) to, "sentences", (long) sents.size(), "output", out));
            int proposed = 0, quoted = 0;
            for (Map<String, Object> f : parse(out, text, from, to, sents)) {
                f.put("a", attribute((String) f.get("a")));
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

    /**
     * Stops a generation that has started going round in circles: the same line
     * three times. Greedy decoding without a repetition penalty can loop, and a
     * loop would spend the passage's whole token budget on nothing.
     */
    static final class LineWatch {
        private final StringBuilder line = new StringBuilder();
        private final java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        boolean more(String piece) {
            for (int i = 0; i < piece.length(); i++) {
                char c = piece.charAt(i);
                if (c != '\n') { line.append(c); continue; }
                String l = line.toString().trim().toLowerCase(Locale.ROOT);
                line.setLength(0);
                if (l.isEmpty()) continue;
                int n = counts.containsKey(l) ? counts.get(l) + 1 : 1;
                counts.put(l, n);
                if (n >= 3) return false;
            }
            return true;
        }
    }

    /**
     * The sentences of text[from, to), as spans with surrounding space trimmed.
     * A sentence ends at . ! or ? followed by a space, or at a line break;
     * a heading line is a sentence of its own.
     */
    static List<int[]> sentences(String text, int from, int to) {
        List<int[]> out = new ArrayList<>();
        int start = from;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            boolean end = c == '\n'
                    || (c == '.' || c == '!' || c == '?') && (i + 1 >= to || Character.isWhitespace(text.charAt(i + 1)))
                       && !abbreviation(text, start, i);
            if (!end) continue;
            addSentence(text, start, c == '\n' ? i : i + 1, out);
            start = i + 1;
        }
        addSentence(text, start, to, out);
        return out;
    }

    private static void addSentence(String text, int s, int e, List<int[]> out) {
        while (s < e && Character.isWhitespace(text.charAt(s))) s++;
        while (e > s && Character.isWhitespace(text.charAt(e - 1))) e--;
        if (e > s && text.substring(s, e).replaceAll("[#*\\-\\s]", "").length() > 1) out.add(new int[]{s, e});
    }

    /** "Dr." "e.g." "U.S." "Mr." : a dot that doesn't end the sentence. */
    private static boolean abbreviation(String text, int start, int dot) {
        int w = dot;
        while (w > start && Character.isLetter(text.charAt(w - 1))) w--;
        String word = text.substring(w, dot);
        if (word.length() == 1) return true;                             // "U.S." "e.g." "M."
        return java.util.Arrays.asList("Dr", "Mr", "Mrs", "Ms", "St", "vs", "etc", "approx", "No", "Fig").contains(word);
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

    /** Reads the model's lines, "n | entity | attribute | value" with sentence n as the quote. */
    static List<Map<String, Object>> parse(String out, String text, int from, int to, List<int[]> sents) {
        List<Map<String, Object>> facts = new ArrayList<>();
        for (String line0 : out.split("\n")) {
            String line = line0.trim();
            if (line.startsWith("- ") || line.startsWith("* ")) line = line.substring(2).trim();
            if (line.isEmpty() || line.equalsIgnoreCase("NONE")) continue;
            String[] parts = line.split("\\|");
            if (parts.length < 4) continue;
            String n = parts[0].trim().replaceAll("^\\[|\\]$", "");
            if (!n.matches("\\d{1,3}")) {                                        // the older form, with a quote
                facts.addAll(parse(line, text, from, to));
                continue;
            }
            int k = Integer.parseInt(n) - 1;
            String e = parts[1].trim(), a = parts[2].trim().toLowerCase(Locale.ROOT).replace(' ', '_'), v = parts[3].trim();
            for (int i = 4; i < parts.length; i++) v += "|" + parts[i];      // a stray | stays in the value, and fails the check
            if (e.isEmpty() || a.isEmpty() || v.isEmpty()) continue;
            long start = -1, end = -1;
            String quote = "sentence " + (k + 1);
            if (k >= 0 && k < sents.size()) {
                start = sents.get(k)[0];
                end = sents.get(k)[1];
                quote = text.substring((int) start, (int) end);
            }
            facts.add(Tx.m("ident", Arrays.asList("name", e), "a", a, "v", v, "nu", 700L,
                    "quote", Tx.m("start", start, "end", end, "text", quote)));
        }
        return facts;
    }

    /** Reads lines in the older form, with the quote written out. A quote the model invented gets offsets -1, and the arbiters refuse it. */
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
