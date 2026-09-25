package app.dilmun.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that a proposed fact is really what its quote says, by rule, with no
 * model involved. A quote that is in the file is not enough: a small model
 * often cuts a phrase short ("anthropology is_a vast" from "a vast field"),
 * takes a modifier for the thing ("biological is_a ..." from "biological
 * anthropology is ..."), or names a thing as itself. So the arbiters also ask:
 *
 *   - are entity and value different things?
 *   - is the value in the quote, and the entity in the quote's sentence, as
 *     whole words? (The shortest quote often leaves out the subject: "used to
 *     treat fever". A sentence that opens with "It" or "They" may take its
 *     subject from the sentence before.)
 *   - does the value end where its phrase ends? English noun phrases end on
 *     their head noun, so a value followed straight away by another plain word
 *     ("vast" + "field") has been cut short.
 *   - for is_a and defined_as, does the quote link them the way the attribute
 *     says: "E is a V", "E, a V", "E (a V)", "V such as E", "E is defined as V"?
 *     Only link words may come between them, so "biological" in "biological
 *     anthropology is a study" is not the thing that is a study.
 *
 * Plain plurals match ("snake" finds "snakes"). A quote in the pattern agent's
 * "entity | attribute | value" form must name exactly that fact.
 *
 * A fact that holds up is then written as concepts ({@link #concept}): "a vast
 * field of study" becomes field_of_study, so every source that says it lands
 * on the same key, and support adds up.
 */
public final class Grounding {
    private Grounding() {}

    /** Words that can follow a complete value: the next phrase starts, or a verb or clause follows. */
    static final Set<String> AFTER_VALUE = set(
            "of", "that", "which", "who", "whom", "whose", "where", "when", "while", "in", "on", "at", "and", "or", "nor",
            "but", "with", "without", "for", "to", "from", "by", "as", "than", "because", "including", "such", "like",
            "is", "are", "was", "were", "be", "been", "being", "has", "have", "had", "can", "could", "may", "might",
            "will", "would", "should", "must", "do", "does", "did", "used", "called", "known", "based", "found",
            "into", "within", "between", "among", "through", "during", "after", "before", "about", "over", "under",
            "since", "until", "if", "so", "not", "also", "only", "it", "its", "they", "their", "this", "these", "those",
            "e.g", "i.e", "etc", "via", "per", "whereas", "although", "though", "unless", "whether", "yet", "then");

    /**
     * Modifiers a concept drops: they grade or frame a thing without changing what
     * it is ("a vast field of study" is a field of study). A fixed list, so the
     * same phrase always gives the same concept.
     */
    static final Set<String> MODIFIERS = set(
            "vast", "broad", "wide", "large", "small", "big", "huge", "enormous", "great", "important", "key", "major",
            "main", "central", "primary", "principal", "basic", "fundamental", "essential", "unique", "distinctive",
            "particular", "specific", "certain", "various", "different", "diverse", "many", "several", "some", "most",
            "rich", "complex", "simple", "new", "old", "modern", "true", "real", "whole", "entire", "general", "common",
            "typical", "special", "significant", "powerful", "fascinating", "interesting", "famous", "well-known",
            "so-called", "very", "highly", "increasingly", "relatively", "in-depth", "overall", "such", "this", "that",
            "these", "those", "our", "their", "its", "his", "her", "one", "own");

    /** Words that may come between entity and value in "E is a V" and its variants. */
    static final Set<String> LINK = set(
            "is", "are", "was", "were", "be", "being", "been", "a", "an", "the", "one", "of", "type", "types", "kind",
            "kinds", "form", "forms", "sort", "class", "member", "members", "variety", "example", "also", "still",
            "generally", "usually", "often", "commonly", "typically", "mainly", "primarily", "essentially",
            "considered", "regarded", "classified", "known", "called", "termed", "described", "defined", "as",
            "refers", "refer", "referred", "to", "means", "mean", "denotes", "just", "describe", "describes", "used",
            ",", "(", ":", "—", "–");

    static final Set<String> LINK_MOD = union(LINK, MODIFIERS);

    /** A singular "is" needs one of these before its value, or the value is an adjective: "anthropology is vast". */
    static final Set<String> NOUN_CUE = set(
            "a", "an", "the", "one", "type", "kind", "form", "sort", "class", "member", "variety", "example",
            ",", "(", ":", "—", "–", "called", "termed", "known", "defined", "refers", "means", "denotes", "describe", "describes");

    /** "V is called E", "V known as E", "V, referred to as E": a name given to a thing. */
    static final Set<String> NAMING = set("called", "known", "referred", "termed", "named", "dubbed");
    static final Set<String> NAMING_LINK = set("is", "are", "was", "were", "also", "often", "commonly", "usually", "locally",
            "sometimes", "generally", "called", "known", "referred", "termed", "named", "dubbed", "as", "to", "a", "an", "the", ",");

    /** Words that must be among the link words: the quote has to actually say "is", not just list both. */
    static final Set<String> COPULA = set(
            "is", "are", "was", "were", ",", "(", ":", "—", "–", "called", "termed", "refers", "means", "denotes", "defined", "describe", "describes");

    /** Between value and entity in "V such as E", "V, including E", "V like E". */
    static final Set<String> LINK_BACK = set("such", "as", "like", "including", "e.g", "for", "example", ",", "(", ":", "especially", "notably");
    static final Set<String> CUE_BACK = set("such", "like", "including", "e.g", "example", "especially", "notably");

    private static final Set<String> ARTICLES = set("a", "an", "the");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+(?:['’.\\-][\\p{L}\\p{N}]+)*|[,;:()\\[\\]—–.!?\"“”]");
    private static final Set<String> DETERMINERS = set("a", "an", "the", "this", "that", "these", "those");
    private static final Set<String> CALL = set("call", "calls", "called");
    private static final Set<String> TITLES = set("Dr", "Mr", "Mrs", "Ms", "St", "Prof", "Sr", "Jr");

    private static final Set<String> PRONOUNS = set("it", "they", "this", "these", "its", "their", "he", "she", "such");

    /** Checks against the quote alone, as if it were a whole sentence. */
    public static String check(String entity, String attribute, String value, String quote) {
        return check(entity, attribute, value, quote, quote, "");
    }

    /**
     * Null when the fact holds up against its quote, or the reason it doesn't.
     * sentence: the quote's whole sentence in the source; before: the sentence before it.
     */
    public static String check(String entity, String attribute, String value, String quote, String sentence, String before) {
        if (quote.indexOf('|') >= 0) return checkLine(entity, attribute, value, quote);
        List<String> e = words(entity), v = words(value), q = tokens(sentence);
        if (e.isEmpty() || v.isEmpty()) return "the fact is incomplete";
        if (stems(e).equals(stems(v))) return "the value repeats the entity";
        if (find(tokens(quote), v).isEmpty()) return "the value is not in the quote";
        List<Integer> es = find(q, e), vs = find(q, v);
        if (vs.isEmpty()) return "the value is not in the quote";
        if (es.isEmpty()) {
            boolean anaphor = !q.isEmpty() && PRONOUNS.contains(q.get(0)) && !find(tokens(before), e).isEmpty();
            if (!anaphor || "is_a".equals(attribute) || "defined_as".equals(attribute)) return "the entity is not in the quote's sentence";
        }
        List<String> raw = rawTokens(sentence);
        boolean relation = "is_a".equals(attribute) || "defined_as".equals(attribute);
        if (relation) {
            for (int a : es) for (int b : vs) if (linked(attribute, q, raw, a, e.size(), b, v.size())) return null;
            return "the quote does not say that " + entity.trim() + " " + ("is_a".equals(attribute) ? "is a" : "is defined as") + " " + value.trim();
        }
        if ("date".equals(attribute)) return null;                 // "the 1992 film": a date is whole on its own
        for (int at : vs) if (endsPhrase(q, at + v.size())) return null;
        return "the value is cut short: the quote goes on to \"" + q.get(vs.get(0) + v.size()) + "\"";
    }

    /** Whether the entity at a and the value at b are linked the way the relation says. */
    private static boolean linked(String attribute, List<String> q, List<String> raw, int a, int en, int b, int vn) {
        boolean isA = "is_a".equals(attribute);
        if (b >= a + en) {                                            // E ... V
            List<String> between = q.subList(a + en, b);
            if (links(between, LINK_MOD, COPULA) && endsPhrase(q, b + vn)) {
                boolean singular = between.contains("is") || between.contains("was");
                boolean noun = false;
                for (String t : between) if (NOUN_CUE.contains(t)) noun = true;
                if (!isA || !singular || noun) return true;         // "anthropology is vast": an adjective, not a kind
            }
            // "call this process of acquiring culture enculturation": call V E, with E after V
        }
        if (a >= b + vn) {                                            // V ... E
            List<String> between = q.subList(b + vn, a);
            if (isA && links(between, LINK_BACK, CUE_BACK)) return true;        // "NSAIDs such as ibuprofen"
            if (links(between, NAMING_LINK, NAMING)) return true;               // "this practice is called fieldwork"
            if (!isA && between.size() == 1 && ":".equals(between.get(0))) return true; // "...distinctive cultures: holism"
            if (between.isEmpty()) {
                int c = b - 1;
                while (c >= 0 && (DETERMINERS.contains(q.get(c)) || MODIFIERS.contains(q.get(c)))) c--;
                if (c >= 0 && CALL.contains(q.get(c))) return true;             // "we call this process ... enculturation"
                // "the Dutch primatologist Carel van Schaik": a title before a proper name
                if (isA && a > 0 && proper(raw.get(a)) && !proper(raw.get(a - 1))) return true;
            }
        }
        return false;
    }

    private static boolean proper(String t) {
        return !t.isEmpty() && Character.isUpperCase(t.charAt(0));
    }

    /**
     * The concept a phrase names, as a key: leading articles and grading words
     * dropped, the head noun made singular, words joined by "_". Proper names
     * keep their capitals. "a vast field of study" → field_of_study;
     * "Primates" → primate; "Carel van Schaik" → Carel_van_Schaik.
     */
    public static String concept(String phrase) { return concept(phrase, null); }

    /**
     * context: the sentence the phrase came from, or null. A word counts as a
     * name when it's an acronym, or when the source capitalizes it mid-sentence
     * ("in Uganda"); a capital that only starts a sentence ("Primates are") does
     * not make a name. Without a context, a capital later in the phrase marks a
     * name ("West African country", "Carel van Schaik").
     */
    public static String concept(String phrase, String context) {
        List<String> ws = new ArrayList<>();
        for (String t : rawTokens(phrase)) if (Character.isLetterOrDigit(t.charAt(0))) ws.add(t);
        while (ws.size() > 1 && (ARTICLES.contains(ws.get(0).toLowerCase(Locale.ROOT))
                || MODIFIERS.contains(ws.get(0).toLowerCase(Locale.ROOT)))) ws.remove(0);
        if (ws.isEmpty()) return "";
        Set<String> named = null;
        if (context != null) {
            named = new HashSet<>();
            List<String> ct = rawTokens(context);
            for (int i = 1; i < ct.size(); i++) {
                String prev = ct.get(i - 1);
                boolean abbrev = ".".equals(prev) && i >= 2 && TITLES.contains(ct.get(i - 2));   // "Dr. Owsley" is mid-sentence
                boolean starts = !abbrev && (".".equals(prev) || "!".equals(prev) || "?".equals(prev) || "\"".equals(prev) || "“".equals(prev) || ":".equals(prev));
                if (proper(ct.get(i)) && !starts) named.add(ct.get(i));
            }
        }
        boolean later = false;
        for (int i = 1; i < ws.size(); i++) if (proper(ws.get(i))) later = true;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < ws.size(); i++) {
            String w = ws.get(i);
            boolean acronym = w.length() > 1 && Character.isUpperCase(w.charAt(0)) && Character.isUpperCase(w.charAt(1));
            boolean keep = proper(w) && (acronym || (named != null ? named.contains(w) : i > 0 || later));
            out.add(keep ? w : w.toLowerCase(Locale.ROOT));
        }
        ws = out;
        int head = ws.indexOf("of") > 0 ? ws.indexOf("of") - 1 : ws.size() - 1;
        String h = ws.get(head);
        if (!proper(h)) ws.set(head, stem(h));
        else if (h.length() > 2 && h.endsWith("s") && Character.isUpperCase(h.charAt(h.length() - 2))) ws.set(head, h.substring(0, h.length() - 1)); // NSAIDs
        return String.join("_", ws);
    }

    /**
     * A fact the model cut short, finished from its sentence: the value run on to
     * where its phrase ends ("vast" → "vast field of study"), the entity run on
     * by up to three words ("biological" → "biological anthropology"). Returns
     * {entity, value} for the first version that passes every check, or null.
     * The repair only ever takes words from the sentence, so it can't invent.
     */
    public static String[] repair(String entity, String attribute, String value, String quote, String sentence, String before) {
        if (quote.indexOf('|') >= 0 || "date".equals(attribute)) return null;
        List<String> raw = rawTokens(sentence), q = tokens(sentence);
        List<Integer> es = find(q, words(entity)), vs = find(q, words(value));
        int en = words(entity).size(), vn = words(value).size();
        List<String> values = new ArrayList<>();
        values.add(value);
        for (int b : vs) {
            int end = b + vn;
            // run on through the phrase, and through "of" into its complement: "vast" → "vast field of study"
            while (end < q.size() && end - b < vn + 6 && Character.isLetterOrDigit(q.get(end).charAt(0))
                    && (!endsPhrase(q, end) || "of".equals(q.get(end)) && end + 1 < q.size() && !endsPhrase(q, end + 1))) end++;
            if (end > b + vn) values.add(join(raw, b, end));
        }
        List<String> entities = new ArrayList<>();
        entities.add(entity);
        for (int a : es)
            for (int k = 1; k <= 3 && a + en + k <= q.size(); k++) {
                String t = q.get(a + en + k - 1);
                if (!Character.isLetterOrDigit(t.charAt(0)) || LINK.contains(t) || AFTER_VALUE.contains(t)) break;
                entities.add(join(raw, a, a + en + k));
            }
        for (String e : entities)
            for (String v : values) {
                if (e.equals(entity) && v.equals(value)) continue;
                if (check(e, attribute, v, quote, sentence, before) == null) return new String[]{e, v};
            }
        return null;
    }

    private static String join(List<String> raw, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(raw.get(i));
        }
        return sb.toString();
    }

    /** The pattern agent quotes a whole "entity | attribute | value" line. */
    private static String checkLine(String entity, String attribute, String value, String quote) {
        String line = quote.trim();
        if (line.startsWith("- ") || line.startsWith("* ")) line = line.substring(2).trim();
        String[] parts = line.split("\\|", -1);
        if (parts.length != 3 || !parts[0].trim().equalsIgnoreCase(entity.trim())
                || !parts[1].trim().toLowerCase(Locale.ROOT).replace(' ', '_').equals(attribute)
                || !parts[2].trim().equalsIgnoreCase(value.trim()))
            return "the quote does not state this fact";
        if (stems(words(entity)).equals(stems(words(value)))) return "the value repeats the entity";
        return null;
    }

    private static boolean endsPhrase(List<String> q, int i) {
        if (i >= q.size()) return true;
        String t = q.get(i);
        if (!Character.isLetterOrDigit(t.charAt(0))) return true;
        return AFTER_VALUE.contains(t) || t.endsWith("ly") || t.endsWith("ing") && t.length() > 5;   // "an anthropologist working for"
    }

    private static boolean links(List<String> between, Set<String> allowed, Set<String> needed) {
        if (between.isEmpty()) return false;
        boolean cue = false;
        for (String t : between) {
            if (!allowed.contains(t)) return false;
            if (needed.contains(t)) cue = true;
        }
        return cue;
    }

    /** Positions where the words occur in a row in the quote, plurals matching singulars. */
    private static List<Integer> find(List<String> q, List<String> w) {
        List<Integer> out = new ArrayList<>();
        List<String> ws = stems(w);
        outer:
        for (int i = 0; i + ws.size() <= q.size(); i++) {
            for (int j = 0; j < ws.size(); j++) if (!stem(q.get(i + j)).equals(ws.get(j))) continue outer;
            out.add(i);
        }
        return out;
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String t : rawTokens(s)) out.add(t.toLowerCase(Locale.ROOT));
        return out;
    }

    /** Tokens with their case, for telling a name from a word. Underscores join a concept's words. */
    static List<String> rawTokens(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(s.replace('_', ' '));
        while (m.find()) out.add(m.group().replace('’', '\''));
        return out;
    }

    /** The words of an entity or value, without a leading article. */
    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String t : tokens(s)) if (Character.isLetterOrDigit(t.charAt(0))) out.add(t);
        while (!out.isEmpty() && ARTICLES.contains(out.get(0))) out.remove(0);
        return out;
    }

    private static List<String> stems(List<String> ws) {
        List<String> out = new ArrayList<>();
        for (String w : ws) out.add(stem(w));
        return out;
    }

    private static final Set<String> INVARIANT = set("species", "series", "means", "news", "analysis", "basis", "crisis",
            "thesis", "hypothesis", "diagnosis", "status", "virus", "corpus", "genus", "chaos", "ethos");

    static String stem(String w) {
        if (INVARIANT.contains(w) || w.endsWith("ics")) return w;
        if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 4 && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("sses") || w.endsWith("xes")))
            return w.substring(0, w.length() - 2);
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is"))
            return w.substring(0, w.length() - 1);
        return w;
    }

    private static Set<String> set(String... s) { return new HashSet<>(Arrays.asList(s)); }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.addAll(b);
        return out;
    }
}
