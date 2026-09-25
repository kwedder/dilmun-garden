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

    /** Words that may come between entity and value in "E is a V" and its variants. */
    static final Set<String> LINK = set(
            "is", "are", "was", "were", "be", "being", "been", "a", "an", "the", "one", "of", "type", "types", "kind",
            "kinds", "form", "forms", "sort", "class", "member", "members", "variety", "example", "also", "still",
            "generally", "usually", "often", "commonly", "typically", "mainly", "primarily", "essentially",
            "considered", "regarded", "classified", "known", "called", "termed", "described", "defined", "as",
            "refers", "refer", "referred", "to", "means", "mean", "denotes", "just",
            ",", "(", ":", "—", "–");

    /** Words that must be among the link words: the quote has to actually say "is", not just list both. */
    static final Set<String> COPULA = set(
            "is", "are", "was", "were", ",", "(", ":", "—", "–", "called", "termed", "refers", "means", "denotes", "defined");

    /** Between value and entity in "V such as E", "V, including E", "V like E". */
    static final Set<String> LINK_BACK = set("such", "as", "like", "including", "e.g", "for", "example", ",", "(", ":", "especially", "notably");
    static final Set<String> CUE_BACK = set("such", "like", "including", "e.g", "example", "especially", "notably");

    private static final Set<String> ARTICLES = set("a", "an", "the");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+(?:['’.\\-][\\p{L}\\p{N}]+)*|[,;:()\\[\\]—–.!?\"“”]");

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
        boolean complete = false;
        for (int at : vs) if (endsPhrase(q, at + v.size())) { complete = true; break; }
        if (!complete) return "the value is cut short: the quote goes on to \"" + q.get(vs.get(0) + v.size()) + "\"";
        if ("is_a".equals(attribute) || "defined_as".equals(attribute)) {
            for (int a : es) for (int b : vs) {
                if (b >= a + e.size() && links(q.subList(a + e.size(), b), LINK, COPULA) && endsPhrase(q, b + v.size())) return null;
                if ("is_a".equals(attribute) && a >= b + v.size() && links(q.subList(b + v.size(), a), LINK_BACK, CUE_BACK)) return null;
            }
            return "the quote does not say that " + entity.trim() + " " + ("is_a".equals(attribute) ? "is a" : "is defined as") + " " + value.trim();
        }
        return null;
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
        return AFTER_VALUE.contains(t) || t.endsWith("ly");
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
        Matcher m = TOKEN.matcher(s.toLowerCase(Locale.ROOT));
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

    static String stem(String w) {
        if (w.length() > 4 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 4 && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("sses") || w.endsWith("xes")))
            return w.substring(0, w.length() - 2);
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is"))
            return w.substring(0, w.length() - 1);
        return w;
    }

    private static Set<String> set(String... s) { return new HashSet<>(Arrays.asList(s)); }
}
