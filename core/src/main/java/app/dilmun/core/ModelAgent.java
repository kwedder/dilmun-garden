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
    private List<String> notes = new ArrayList<>();

    public ModelAgent(Llm llm, List<String> attributes, Progress progress) {
        this.llm = llm;
        this.attributes = new ArrayList<>();
        for (String a : attributes) if (!"name".equals(a)) this.attributes.add(a);
        this.progress = progress;
    }

    @Override public String id() { return llm.id(); }

    /** The arbiters' notes on this model's last results, put in its briefing (Engine.notes). */
    public ModelAgent briefed(List<String> notes) {
        this.notes = notes == null ? new ArrayList<String>() : new ArrayList<>(notes);
        return this;
    }

    /** The briefing the arbiters give this agent. */
    String systemPrompt() { return Briefing.extract(attributes, notes); }

    /**
     * A small model sometimes numbers a fact one or two sentences off. When the
     * sentence it named holds neither its entity nor its value, but a neighbour
     * within two holds both, the fact points at that neighbour instead.
     */
    @SuppressWarnings("unchecked")
    static void repoint(Map<String, Object> f, String text, List<int[]> sents) {
        Map<String, Object> q = (Map<String, Object>) f.get("quote");
        long start = ((Number) q.get("start")).longValue();
        int at = -1;
        for (int i = 0; i < sents.size(); i++) if (sents.get(i)[0] == start) at = i;
        if (at < 0) return;
        List<String> e = Grounding.words(String.valueOf(((List<Object>) f.get("ident")).get(1))), v = Grounding.words(String.valueOf(f.get("v")));
        if (e.isEmpty() || v.isEmpty() || holds(text, sents.get(at), v)) return;
        for (int d = 1; d <= 2; d++)
            for (int k : new int[]{at - d, at + d})
                if (k >= 0 && k < sents.size() && holds(text, sents.get(k), v) && holds(text, sents.get(k), e)) {
                    int[] x = sents.get(k);
                    f.put("quote", Tx.m("start", (long) x[0], "end", (long) x[1], "text", text.substring(x[0], x[1])));
                    return;
                }
    }

    private static boolean holds(String text, int[] span, List<String> words) {
        return java.util.Collections.indexOfSubList(Grounding.tokens(text.substring(span[0], span[1])), words) >= 0;
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
            List<int[]> sents = readable(text, from, to);
            List<List<String>> menus = new ArrayList<>();
            for (int[] x : sents) menus.add(Grounding.phrases(text.substring(x[0], x[1])));
            List<Map<String, Object>> ruled = new ArrayList<>();          // the rules read what they can for certain, at no model cost
            for (int[] x : sents)
                for (String[] h : Grounding.harvest(text.substring(x[0], x[1])))
                    ruled.add(Tx.m("ident", Arrays.asList("name", h[0]), "a", h[1], "v", h[2], "nu", Policy.PATTERN_NU, "by", "rules",
                            "quote", Tx.m("start", (long) x[0], "end", (long) x[1], "text", text.substring(x[0], x[1]))));
            StringBuilder user = new StringBuilder("Sentences:\n");
            for (int i = 0; i < sents.size(); i++)
                if (menus.get(i).size() >= 2)                               // a fact needs two things to relate
                    user.append(Briefing.sentence(i + 1, text.substring(sents.get(i)[0], sents.get(i)[1]), menus.get(i)));
            List<String> shown = examples(ruled, sents, menus);
            if (!shown.isEmpty()) {
                user.append("\nThe rules already reported these, which shows the form (don't repeat them):\n");
                for (String l : shown) user.append(l).append('\n');
            }
            List<String[]> msgs = new ArrayList<>();
            msgs.add(new String[]{"system", systemPrompt()});
            msgs.add(new String[]{"user", user.toString()});
            final LineWatch watch = new LineWatch();
            String out = llm.generate(msgs, MAX_TOKENS, 0f, false, Briefing.extractGrammar(attributes), piece -> {
                String t = new String(piece, java.nio.charset.StandardCharsets.UTF_8);
                if (progress != null) progress.text(t);
                return watch.more(t);
            });
            raw.add(Tx.m("from", (long) from, "to", (long) to, "sentences", (long) sents.size(), "menus", new ArrayList<Object>(menus), "output", out));
            List<Map<String, Object>> found = new ArrayList<>(ruled);
            for (Map<String, Object> f : parse(out, text, from, to, sents, menus)) {
                f.put("a", attribute((String) f.get("a")));
                repoint(f, text, sents);
                found.add(f);
            }
            int proposed = 0, quoted = 0;
            for (Map<String, Object> f : found) {
                String key = Grounding.concept(String.valueOf(((List<?>) f.get("ident")).get(1))).toLowerCase(Locale.ROOT) + "|" + f.get("a")
                        + "|" + Grounding.concept(String.valueOf(f.get("v"))).toLowerCase(Locale.ROOT);   // the rules' fact and the model's, once
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

    /** Headings that start a list of works rather than prose: nothing under them states a fact. */
    static final java.util.regex.Pattern REFERENCES = java.util.regex.Pattern.compile(
            "#+\\s*(references|bibliography|works cited|sources|further reading|suggested reading|notes|footnotes|citations)\\b.*",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * The sentences of a passage worth reading for facts: no headings (they name,
     * they don't state), and nothing under a References or Bibliography heading,
     * which the whole text before the passage decides.
     */
    static List<int[]> readable(String text, int from, int to) {
        boolean refs = false;
        int h = text.lastIndexOf("\n#", from);
        if (text.startsWith("#") && h < 0) h = -1;
        String before = h >= 0 ? text.substring(h + 1, Math.max(h + 1, text.indexOf('\n', h + 1) < 0 ? text.length() : text.indexOf('\n', h + 1))) : "";
        if (!before.isEmpty() && before.charAt(0) == '#') refs = REFERENCES.matcher(before.trim()).matches();
        List<int[]> out = new ArrayList<>();
        for (int[] x : sentences(text, from, to)) {
            String s = text.substring(x[0], x[1]);
            if (s.startsWith("#")) { refs = REFERENCES.matcher(s.trim()).matches(); continue; }
            if (!refs) out.add(x);
        }
        return out;
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

    /**
     * Up to three of the rules' facts for this passage, written the way the model
     * must answer, where both sides are on their sentence's menu.
     */
    @SuppressWarnings("unchecked")
    static List<String> examples(List<Map<String, Object>> ruled, List<int[]> sents, List<List<String>> menus) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> f : ruled) {
            if (out.size() >= 3) break;
            long start = ((Number) ((Map<String, Object>) f.get("quote")).get("start")).longValue();
            for (int i = 0; i < sents.size(); i++) {
                if (sents.get(i)[0] != start || menus.get(i).size() < 2) continue;
                int e = letter(menus.get(i), String.valueOf(((List<Object>) f.get("ident")).get(1))), v = letter(menus.get(i), String.valueOf(f.get("v")));
                if (e >= 0 && v >= 0 && e != v)
                    out.add((i + 1) + " | " + Grounding.LETTERS.charAt(e) + " | " + f.get("a") + " | " + Grounding.LETTERS.charAt(v));
            }
        }
        return out;
    }

    private static int letter(List<String> menu, String phrase) {
        String c = Grounding.concept(phrase).toLowerCase(Locale.ROOT);
        for (int i = 0; i < menu.size(); i++) if (Grounding.concept(menu.get(i)).toLowerCase(Locale.ROOT).equals(c)) return i;
        return -1;
    }

    /**
     * Reads the model's lines. "n | letter | relation | letter" names two phrases
     * off sentence n's menu; the sentence is the quote. Lines in the older forms,
     * with the entity and value written out, are read too.
     */
    static List<Map<String, Object>> parse(String out, String text, int from, int to, List<int[]> sents, List<List<String>> menus) {
        List<Map<String, Object>> facts = new ArrayList<>();
        StringBuilder rest = new StringBuilder();
        for (String line0 : out.split("\n")) {
            String[] parts = line0.trim().split("\\s*\\|\\s*");
            if (parts.length == 4 && parts[0].matches("\\d{1,3}") && parts[1].matches("[a-z]") && parts[3].matches("[a-z]")) {
                int k = Integer.parseInt(parts[0]) - 1, x = parts[1].charAt(0) - 'a', y = parts[3].charAt(0) - 'a';
                if (k < 0 || k >= sents.size() || x == y || x >= menus.get(k).size() || y >= menus.get(k).size()) continue;  // not on the menu: nothing to name
                int[] span = sents.get(k);
                facts.add(Tx.m("ident", Arrays.asList("name", menus.get(k).get(x)), "a", parts[2].trim().toLowerCase(Locale.ROOT),
                        "v", menus.get(k).get(y), "nu", 700L,
                        "quote", Tx.m("start", (long) span[0], "end", (long) span[1], "text", text.substring(span[0], span[1]))));
            } else rest.append(line0).append('\n');
        }
        facts.addAll(parse(rest.toString(), text, from, to, sents));
        return facts;
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
