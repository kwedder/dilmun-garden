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
            "into", "within", "between", "among", "through", "during", "after", "before", "about", "over", "under", "upon",
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

    /** What makes "E ... V" a definition: a word that defines, not a comma. */
    static final Set<String> DEFINES = set("is", "are", "was", "were", "means", "mean", "refers", "defined", "called", "termed",
            "denotes", "describe", "describes", ":", "—", "–");

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
    private static final Pattern TOKEN = Pattern.compile("\\p{Lu}\\.(?=\\s+\\p{Lu})|[\\p{L}\\p{N}]+(?:['’.\\-][\\p{L}\\p{N}]+)*|[,;:()\\[\\]—–.!?\"“”]");
    private static final Set<String> DETERMINERS = set("a", "an", "the", "this", "that", "these", "those");
    private static final Set<String> CALL = set("call", "calls", "called");
    private static final Set<String> TITLES = set("Dr", "Mr", "Mrs", "Ms", "St", "Prof", "Sr", "Jr");

    private static final Set<String> PRONOUNS = set("it", "they", "this", "these", "its", "their", "he", "she", "such");

    /**
     * Words that make a phrase a clause, not a name: a concept holding one of
     * these ("exploring_how_smartphones_take", "who", "we") isn't a thing.
     */
    static final Set<String> CLAUSE = set("how", "who", "whom", "what", "which", "why", "when", "where", "whether",
            "we", "they", "he", "she", "it", "you", "i", "me", "us", "them", "him", "our", "your", "my", "their", "its",
            "is", "are", "was", "were", "be", "been", "not", "no", "can", "could", "may", "might", "will", "would",
            "should", "must", "do", "does", "did", "have", "has", "had", "if", "because", "other", "self", "something",
            "anything", "everything", "nothing", "someone", "everyone", "there", "here", "often", "rather", "perhaps",
            "sometimes", "still", "well", "ever", "never", "always", "also", "even", "just", "instead", "yet", "same", "lot",
            "worth", "kind", "sort", "more", "less", "fewer");

    /** Words ending in -ly that are things, not manners. */
    private static final Set<String> LY_NOUNS = set("family", "italy", "ally", "assembly", "supply", "anomaly", "monopoly",
            "butterfly", "july", "reply", "belly", "lily", "rally", "sicily", "folly", "jelly", "bully", "melancholy");

    /**
     * Words that describe rather than name, after an "are" with no article:
     * "anthropologists are committed", "Indians are familiar". A fixed list and a
     * few endings that English uses for adjectives and participles.
     */
    private static final Set<String> ADJECTIVES = set("familiar", "curious", "same", "similar", "different", "able", "aware",
            "likely", "cool", "violent", "ignorant", "inaccurate", "widespread", "contemporary", "complex", "fine", "united",
            "worth", "better", "best", "responsible", "necessary", "possible", "rare", "free", "open", "present", "absent",
            "available", "true", "real", "unique", "vast", "important", "central", "common", "mobile", "native", "primitive",
            "superior", "inferior", "backward", "civilized", "enlightened", "unequal", "equal", "human", "natural", "normal",
            "polarized", "reciprocal", "universal", "due", "subject", "prone", "key", "interested", "busy");

    /** Longest concept, in words, not counting "of" and "and". Anything longer is a clause cut from the sentence. */
    static final int CONCEPT_WORDS = 5;

    /**
     * The words a sentence must use, between entity and value, to state each
     * relation. Without one, both words merely appear in the same sentence:
     * "red | part_of | color" from "...from pinkish beige to dark brown..." is
     * co-occurrence, not a fact.
     */
    static final java.util.Map<String, Set<String>> CUES = new java.util.HashMap<>();
    static {
        CUES.put("part_of", set("part", "parts", "member", "members", "among", "one", "include", "includes", "included", "including",
                "comprise", "comprises", "comprised", "comprising", "consist", "consists", "belong", "belongs", "component",
                "components", "subfield", "subfields", "branch", "branches", "within", "division", "section"));
        CUES.put("contains", set("contain", "contains", "contained", "containing", "include", "includes", "included", "including",
                "comprise", "comprises", "consist", "consists", "has", "have", "with", "hold", "holds", "house", "houses"));
        CUES.put("causes", set("cause", "causes", "caused", "causing", "lead", "leads", "led", "result", "results", "resulted",
                "resulting", "produce", "produces", "produced", "trigger", "triggers", "triggered", "drive", "drives", "drove",
                "due", "because", "responsible", "effect", "effects", "create", "creates", "created", "shape", "shapes",
                "shaped", "promote", "promotes", "promoted", "brought", "bring", "brings", "forced", "force", "forces"));
        CUES.put("treats", set("treat", "treats", "treated", "treating", "treatment", "cure", "cures", "cured", "relieve",
                "relieves", "relieved", "heal", "heals", "healing", "remedy", "therapy", "against", "for"));
        CUES.put("located_in", set("in", "at", "on", "near", "located", "found", "inside", "within", "of", "across", "throughout"));
        CUES.put("author", set("by", "wrote", "written", "writes", "write", "author", "authored", "'s", "his", "her"));
    }

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
        String bad = notAThing(e, attribute, false);
        if (bad == null && "is_a".equals(attribute) && describes(v, q, find(q, v))) bad = "the value describes, it doesn't name a kind";
        if (bad == null) bad = notAThing(v, attribute, true);
        if (bad != null) return bad;
        if (!"defined_as".equals(attribute) && (containsAll(stems(v), stems(e)) || containsAll(stems(e), stems(v))))
            return "one side just repeats the other";
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
        boolean complete = false;
        for (int at : vs) if (endsPhrase(q, at + v.size())) complete = true;
        if (!complete) return "the value is cut short: the quote goes on to \"" + q.get(vs.get(0) + v.size()) + "\"";
        if (("located_in".equals(attribute) || "author".equals(attribute)) && !named(raw, q, vs))
            return "the value is not a name: " + attribute + " needs a place or a person";
        Set<String> cues = CUES.get(attribute);
        if (cues == null) return null;
        if (es.isEmpty()) {                                          // "It lies in Peru": the cue before the value
            for (int b : vs) for (int i = 0; i < b; i++) if (cues.contains(q.get(i))) return null;
        }
        for (int a : es) for (int b : vs) {
            int lo = Math.min(a + e.size(), b + v.size()), hi = Math.max(a, b);
            if (hi - lo > NEAR) continue;                             // too far apart to be one statement
            if ("located_in".equals(attribute)) {                    // "E in V", "E is located in V": the place right after its link
                List<String> between = q.subList(lo, hi);
                if (b > a && links(between, LOCATED_LINK, cues)) return null;
                // "the Wauja, an indigenous group in Brazil": an apposition, then the place
                int k = between.size();
                while (k > 0 && LOCATED_LINK.contains(between.get(k - 1))) k--;
                if (b > a && k < between.size() && cues.contains(between.get(between.size() - 1 < k ? k : between.size() - 1))
                        && k >= 2 && ",".equals(between.get(0)) && ARTICLES.contains(between.get(1))) return null;
                continue;
            }
            for (int i = lo; i < hi; i++) if (cues.contains(q.get(i))) return null;
        }
        return "the sentence doesn't say " + attribute.replace('_', ' ') + ": no word for it between " + entity.trim() + " and " + value.trim();
    }

    /** Most words between entity and value for a cue to join them. */
    static final int NEAR = 8;

    private static final Set<String> LOCATED_LINK = set("is", "are", "was", "were", "located", "found", "based", "situated",
            "lies", "lie", "in", "at", "on", "near", "within", "inside", "the", ",", "of", "northern", "southern", "eastern",
            "western", "central", "works", "work", "worked", "working", "lives", "live", "lived", "living", "born", "stationed");

    /** Why a phrase isn't a thing (a pronoun, a clause, a date that isn't one), or null. */
    private static String notAThing(List<String> w, String attribute, boolean isValue) {
        String side = isValue ? "the value" : "the entity";
        if (isValue && "date".equals(attribute)) {
            for (String t : w) if (t.matches("\\d{3,4}s?|\\d{1,2}(st|nd|rd|th)|\\d{1,3},\\d{3}")) return null;
            return "the value is not a date";
        }
        if (isValue && "defined_as".equals(attribute)) return null;   // a definition is a phrase by nature
        int n = 0;
        for (String t : w) {
            if (CLAUSE.contains(t)) return side + " is not a thing: \"" + t + "\" makes it a clause or a pronoun";
            if (!"of".equals(t) && !"and".equals(t) && !ARTICLES.contains(t)) n++;
        }
        if (n > CONCEPT_WORDS) return side + " is a clause, not a name (" + n + " words)";
        if (!isValue && w.size() == 1 && (NUMBER_WORDS.contains(w.get(0)) || w.get(0).matches("\\d+")))
            return side + " is only a number";
        if (w.size() == 1 && w.get(0).endsWith("ly") && w.get(0).length() > 4 && !LY_NOUNS.contains(w.get(0)))
            return side + " is not a thing: \"" + w.get(0) + "\" is a manner, not a name";
        boolean graded = true;
        for (String t : w) if (!MODIFIERS.contains(t)) graded = false;
        if (graded) return side + " is only a grading word";
        return null;
    }

    /**
     * Whether an is_a value is an adjective or participle: its last word is one,
     * and no article comes before it where it stands ("are committed", not "are
     * a committed group").
     */
    private static boolean describes(List<String> v, List<String> q, List<Integer> vs) {
        String h = v.get(v.size() - 1);
        boolean adj = ADJECTIVES.contains(h) || PARTICIPLES.contains(h) || h.length() > 4 && (h.endsWith("ed") || h.endsWith("ous") || h.endsWith("ful")
                || h.endsWith("able") || h.endsWith("ible") || h.endsWith("less") || h.endsWith("ical") || h.endsWith("ric")
                || h.endsWith("ive") && !h.endsWith("tive") || h.endsWith("ly") && !LY_NOUNS.contains(h));
        if (!adj) return false;
        if (v.size() == 1 && (ADJECTIVES.contains(h) || PARTICIPLES.contains(h))) return true;   // "a violent" names nothing
        for (int b : vs) {
            int k = b - 1;
            while (k >= 0 && MODIFIERS.contains(q.get(k))) k--;
            if (k >= 0 && ARTICLES.contains(q.get(k))) return false;
        }
        return true;
    }

    private static boolean containsAll(List<String> big, List<String> small) {
        return big.size() > small.size() && big.containsAll(small);
    }

    /** Whether the value is written as a name somewhere it occurs: capitalized mid-sentence, or an acronym. */
    private static boolean named(List<String> raw, List<String> q, List<Integer> vs) {
        for (int b : vs) {
            String t = raw.get(b);
            if (!proper(t)) continue;
            if (b == 0) continue;
            String prev = q.get(b - 1);
            if (!".".equals(prev) && !"!".equals(prev) && !"?".equals(prev)) return true;
        }
        return false;
    }

    /** Whether the entity at a and the value at b are linked the way the relation says. */
    private static boolean linked(String attribute, List<String> q, List<String> raw, int a, int en, int b, int vn) {
        boolean isA = "is_a".equals(attribute);
        if (b >= a + en) {                                            // E ... V
            List<String> between = q.subList(a + en, b);
            boolean defines = false;                                  // "freedom, equal opportunity" is a list, not a definition
            for (String t : between) if (DEFINES.contains(t)) defines = true;
            if (links(between, LINK_MOD, COPULA) && endsPhrase(q, b + vn) && (isA || defines)) {
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
            if (isA && suchAsList(between)) return true;                        // "…, such as political science, religious studies, and economics"
            if (links(between, NAMING_LINK, NAMING)) return true;               // "this practice is called fieldwork"
            if (!isA && between.size() == 1 && ":".equals(between.get(0)) && b > 0
                    && set("word", "term", "name", "concept", "idea", "notion", "label").contains(q.get(b - 1 < 0 ? 0 : Math.max(0, b - 1)))) return true;
            if (!isA && between.size() == 1 && ":".equals(between.get(0)) && vn >= 3) return true; // "...interrelate to form distinctive cultures: holism"
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

    /** "such as A, B, and": what comes before a later item of a "such as" list. */
    private static boolean suchAsList(List<String> between) {
        int k = 0;
        if (k < between.size() && ",".equals(between.get(k))) k++;
        if (k + 1 >= between.size() || !"such".equals(between.get(k)) || !"as".equals(between.get(k + 1))) return false;
        boolean sep = false;
        for (int i = k + 2; i < between.size(); i++) {
            String t = between.get(i);
            if (".".equals(t) || ";".equals(t) || ":".equals(t) || "(".equals(t) || CLAUSE.contains(t) || COPULAS.contains(t)) return false;
            if (",".equals(t) || "and".equals(t) || "or".equals(t)) sep = true;
        }
        String last = between.get(between.size() - 1);
        return sep && (",".equals(last) || "and".equals(last) || "or".equals(last));
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
        for (String t : rawTokens(phrase)) if (Character.isLetterOrDigit(t.charAt(0))) ws.add(t.endsWith(".") ? t.substring(0, t.length() - 1) : t);
        while (ws.size() > 1 && (ARTICLES.contains(ws.get(0).toLowerCase(Locale.ROOT))
                || MODIFIERS.contains(ws.get(0).toLowerCase(Locale.ROOT)) && !proper(ws.get(1)))) ws.remove(0);   // "True Lies" keeps "True"
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
            boolean acronym = Character.isUpperCase(w.charAt(0)) && (w.length() == 1 || Character.isUpperCase(w.charAt(1)));   // "NSAID", the "M" of "Henry M. Stanley"
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
        if (quote.indexOf('|') >= 0 || !("is_a".equals(attribute) || "defined_as".equals(attribute))) return null;
        List<String> raw = rawTokens(sentence), q = tokens(sentence);
        List<Integer> es = find(q, words(entity)), vs = find(q, words(value));
        int en = words(entity).size(), vn = words(value).size();
        List<String> values = new ArrayList<>();
        values.add(value);
        for (int b : vs) {
            int end = b + vn;
            // run on through the phrase, and through "of" into its complement: "vast" → "vast field of study"
            while (end < q.size() && end - b < vn + 3 && Character.isLetterOrDigit(q.get(end).charAt(0)) && !CLAUSE.contains(q.get(end))
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

    // ------------------------------------------------------------ menu

    /** Letters for a sentence's phrase menu. */
    public static final String LETTERS = "abcdefgh";

    /** Verbs that end a phrase: the ones textbooks and papers use to report ("Susan Bayly describes how"). */
    static final Set<String> VERBS = set("describes", "describe", "described", "focuses", "focus", "argues", "argue", "argued",
            "explores", "explore", "examines", "examine", "shows", "show", "suggests", "suggest", "notes", "note", "claims", "claim",
            "finds", "find", "began", "begin", "begins", "became", "become", "becomes", "uses", "use", "involves", "involve",
            "provides", "provide", "creates", "create", "makes", "make", "takes", "take", "gives", "give", "helps", "help",
            "seeks", "seek", "tends", "tend", "remains", "remain", "seems", "seem", "appears", "appear", "says", "say", "said",
            "writes", "wrote", "explains", "explain", "demonstrates", "demonstrate", "emphasizes", "emphasize", "highlights",
            "highlight", "considers", "consider", "documents", "discovers", "discover", "observes", "observe", "records",
            "reveals", "reveal", "concludes", "conclude", "points", "posits", "theorizes", "warns", "warn", "spent", "spend",
            "spends", "developed", "develop", "develops", "lived", "live", "lives", "worked", "work", "works", "studied",
            "studies", "study", "learn", "learned", "learns", "teach", "teaches", "taught", "offer", "offers", "offered",
            "allow", "allows", "allowed", "led", "lead", "leads", "include", "includes", "included", "contain", "contains",
            "cause", "causes", "caused", "shape", "shapes", "shaped", "affect", "affects", "affected", "produce", "produces");

    private static final Set<String> DETERMINER_LIKE = set("a", "an", "the", "this", "that", "these", "those", "every", "each",
            "all", "many", "some", "several", "most", "any", "no", "their", "its", "our", "his", "her", "my", "your");

    /**
     * The things a sentence names, as its phrase menu: noun phrases cut by rule,
     * each a candidate entity or value. A phrase starts after an article, a
     * preposition, a verb-like word or a comma; it ends at a phrase boundary, at
     * a participle, or before a word that an article follows (that word is a
     * verb: "anthropologists study every realm"). At most eight, first come
     * first served, no two naming the same concept, none that isn't a thing.
     */
    public static List<String> phrases(String sentence) {
        List<String> raw = rawTokens(sentence), q = tokens(sentence);
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int n = q.size(), i = 0;
        while (i < n && out.size() < LETTERS.length()) {
            String t = q.get(i);
            boolean word = Character.isLetterOrDigit(t.charAt(0));
            if (!word || CLAUSE.contains(t) || COPULAS.contains(t) || SUBJECT_STOP.contains(t) || PREPOSITIONS.contains(t)
                    || AFTER_VALUE.contains(t) || DETERMINER_LIKE.contains(t) || MODIFIERS.contains(t)
                    || i + 1 < n && DETERMINER_LIKE.contains(q.get(i + 1)) || PARTICIPLES.contains(t)
                    || t.endsWith("ed") && t.length() > 4 || t.endsWith("ly") && t.length() > 4 && !LY_NOUNS.contains(t) && !proper(raw.get(i))
                    || VERBS.contains(t) || t.endsWith("ing") && t.length() > 5 && !(i == 0 || OPENERS.contains(q.get(i - 1))
                            || PREPOSITIONS.contains(q.get(i - 1)))) { i++; continue; }   // "Smoking causes": a gerund that starts a phrase is a noun
            int e = i;
            while (e < n && content(q, i, e) < CONCEPT_WORDS) {
                String u = q.get(e);
                if (!Character.isLetterOrDigit(u.charAt(0)) || CLAUSE.contains(u) || COPULAS.contains(u) || e > i && (
                        PREPOSITIONS.contains(u) && !"of".equals(u) || AFTER_VALUE.contains(u) && !"of".equals(u) || PARTICIPLES.contains(u)
                        || u.endsWith("ed") && u.length() > 4 || e + 1 < n && DETERMINER_LIKE.contains(q.get(e + 1)) && !"of".equals(u)
                        || VERBS.contains(u) && !"of".equals(q.get(e - 1))                 // "field of study": a noun after "of"
                        || u.endsWith("ly") && u.length() > 4 && !LY_NOUNS.contains(u) && !proper(raw.get(e))   // not "Bayly"
                        || u.endsWith("ing") && u.length() > 5 && !needsNoun(q.get(e - 1)))) break;
                if ("of".equals(u) && !(e + 1 < n && Character.isLetterOrDigit(q.get(e + 1).charAt(0)))) break;
                e++;
            }
            while (e > i && ("of".equals(q.get(e - 1)) || ARTICLES.contains(q.get(e - 1)))) e--;   // no dangling "of the"
            if (e > i) {
                String p = join(raw, i, e);
                String c = concept(p).toLowerCase(Locale.ROOT);
                if (!c.isEmpty() && notAThing(words(p), "is_a", false) == null && seen.add(c)) out.add(p);
                i = e;
            } else i++;
        }
        return out;
    }

    // ------------------------------------------------------------ harvest

    /** Where a subject stops, walking back from its verb. */
    private static final Set<String> SUBJECT_STOP = set("a", "an", "the", "this", "these", "those", "that", "and", "or", "but",
            "in", "on", "at", "to", "for", "from", "by", "with", "as", "which", "who", "whom", "whose", "while", "when",
            "where", "although", "because", "if", "so", "than", "then", "also", "simply", "however", "moreover", "thus", "all");
    private static final Set<String> COPULAS = set("is", "are", "was", "were");

    /**
     * The facts a sentence states in the few forms these rules can read for
     * certain, with no model: "E is a V", "E, a V,", "V is called E", "V known
     * as E", "V such as E1, E2 and E3", "the primatologist Carel van Schaik".
     * Each comes back as {entity, attribute, value} and still goes through
     * {@link #check} at the arbiters like any proposal.
     */
    public static List<String[]> harvest(String sentence) {
        List<String[]> out = new ArrayList<>();
        List<String> raw = rawTokens(sentence), q = tokens(sentence);
        int n = q.size();
        for (int i = 0; i < n; i++) {
            String t = q.get(i);
            // E is/are (a|an|the|one of the) V
            if (COPULAS.contains(t) && i > 0 && i + 1 < n && !"not".equals(q.get(i + 1))) {
                int es = subjectStart(q, i);
                int vs = i + 1;
                while (vs < n && (ARTICLES.contains(q.get(vs)) || MODIFIERS.contains(q.get(vs)) || "of".equals(q.get(vs)) && vs > i + 1 && "one".equals(q.get(vs - 1)))) vs++;
                int ve = phraseEnd(q, vs);
                boolean passive = vs < n && (PARTICIPLES.contains(q.get(vs)) || q.get(vs).endsWith("ed") && q.get(vs).length() > 4);
                if (es < i && ve > vs && (cleanStart(q, es) || proper(raw.get(es)) && es > 0) && !passive) {
                    out.add(new String[]{join(raw, es, i), "is_a", join(raw, vs, ve)});
                    // V is called / known as E: a name given to the thing
                    int k = i + 1;
                    while (k < n && ("also".equals(q.get(k)) || "often".equals(q.get(k)) || "commonly".equals(q.get(k)) || "locally".equals(q.get(k)) || "usually".equals(q.get(k)))) k++;
                }
            }
            // V (is|are)? called|known as|referred to as|termed E
            if (NAMING.contains(t) && i > 0 && !("known".equals(t) && !(i + 1 < n && "as".equals(q.get(i + 1))))
                    && !("referred".equals(t) && !(i + 2 < n && "to".equals(q.get(i + 1)) && "as".equals(q.get(i + 2))))) {
                int k = i + 1;
                if (k < n && ("as".equals(q.get(k)) || "to".equals(q.get(k)))) k++;
                if (k < n && "as".equals(q.get(k))) k++;
                while (k < n && ARTICLES.contains(q.get(k))) k++;
                int ee = phraseEnd(q, k);
                int ve = i;
                while (ve > 0 && COPULAS.contains(q.get(ve - 1)) || ve > 0 && ("also".equals(q.get(ve - 1)) || "locally".equals(q.get(ve - 1)) || "often".equals(q.get(ve - 1)) || ",".equals(q.get(ve - 1)))) ve--;
                int vs = subjectStart(q, ve);                                   // "This area of study is called": keeps its "of"
                if (ee > k && vs < ve && cleanStart(q, vs)) out.add(new String[]{join(raw, k, ee), "is_a", join(raw, vs, ve)});
            }
            // E, a V,  /  E (a V)  /  Name, the V of   (", the" only after a name: "the brain, the heart" is a list)
            if ((",".equals(t) || "(".equals(t)) && i > 0 && i + 1 < n && ARTICLES.contains(q.get(i + 1))) {
                int es = subjectStart(q, i);
                int vs = i + 2;
                while (vs < n && MODIFIERS.contains(q.get(vs))) vs++;
                int ve = phraseEnd(q, vs);
                boolean the = "the".equals(q.get(i + 1));
                String next = ve < n ? q.get(ve) : ".";
                boolean list = ",".equals(next) && ve + 1 < n && (ARTICLES.contains(q.get(ve + 1)) || proper(raw.get(ve + 1))
                                || "and".equals(q.get(ve + 1)) || "or".equals(q.get(ve + 1)))
                        || "and".equals(next) || "or".equals(next);
                boolean closes = ",".equals(next) || ".".equals(next) || ")".equals(next) || ";".equals(next) || PREPOSITIONS.contains(next)
                        || next.endsWith("ing") || "who".equals(next) || "which".equals(next);
                boolean named = proper(raw.get(es)) && es > 0;                  // a name marks its own edges: "from Kinshasa, the capital"
                boolean ok = !list && closes && (cleanStart(q, es) || named) && (!the || proper(raw.get(i - 1)) && (",".equals(next) || ".".equals(next) || ")".equals(next) || "of".equals(next)));
                if (ok && es < i && ve > vs) out.add(new String[]{join(raw, es, i), "is_a", join(raw, vs, ve)});
            }
            // V such as E1, E2, and E3  /  V, including E
            // ("including" is left out: "parts of the world, including Brazil" is part_of, not is_a)
            if ("such".equals(t) && i + 1 < n && "as".equals(q.get(i + 1))) {
                int ve = i;
                if (ve > 0 && ",".equals(q.get(ve - 1))) ve--;
                int vs = kindStart(q, ve);
                if (!cleanStart(q, vs)) vs = ve;                                  // "objects made by human beings, such as tools": not human beings
                int k = i + 2;
                boolean last = false;
                while (k < n && vs < ve && !last) {
                    while (k < n && (ARTICLES.contains(q.get(k)) || "and".equals(q.get(k)) || "or".equals(q.get(k)) || ",".equals(q.get(k)))) {
                        if ("and".equals(q.get(k)) || "or".equals(q.get(k))) last = true;   // "…, and economics": the list's last item
                        k++;
                    }
                    int ee = phraseEnd(q, k);
                    if (ee <= k) break;
                    out.add(new String[]{join(raw, k, ee), "is_a", join(raw, vs, ve)});
                    k = ee;
                    if (k >= n || !(",".equals(q.get(k)) || "and".equals(q.get(k)) || "or".equals(q.get(k)))) break;
                }
            }
            // the primatologist Carel van Schaik: a title in lower case, then a name
            if (i > 0 && proper(raw.get(i)) && !proper(raw.get(i - 1)) && Character.isLetter(raw.get(i - 1).charAt(0))
                    && !SUBJECT_STOP.contains(q.get(i - 1)) && !COPULAS.contains(q.get(i - 1)) && !AFTER_VALUE.contains(q.get(i - 1))
                    && !PREPOSITIONS.contains(q.get(i - 1)) && !q.get(i - 1).endsWith("ed")) {
                int ne = i;
                while (ne < n) {
                    if (proper(raw.get(ne)) && raw.get(ne).length() == 1 && ne + 2 < n && ".".equals(q.get(ne + 1)) && proper(raw.get(ne + 2))) ne += 2;  // "Henry M. Stanley"
                    else if (proper(raw.get(ne)) && !(ne > i && raw.get(ne).length() == 1)
                            || ("van".equals(q.get(ne)) || "de".equals(q.get(ne))) && ne + 1 < n && proper(raw.get(ne + 1))) ne++;
                    else break;
                }
                int ts = kindStart(q, i);
                boolean lower = !proper(raw.get(i - 1)) && titleHead(q.get(i - 1));   // the title's head names a role: "Dutch primatologist"
                if (ts < i && ne - i >= 2 && lower) out.add(new String[]{join(raw, i, ne), "is_a", join(raw, ts, i)});
            }
        }
        return out;
    }

    private static final Set<String> PREPOSITIONS = set("by", "with", "from", "to", "in", "on", "at", "for", "of", "upon", "into",
            "about", "among", "between", "through", "during", "against", "toward", "towards", "under", "over", "across",
            "around", "beyond", "within", "without", "behind", "despite", "near", "throughout", "via", "per");

    /** Irregular participles: "objects made by", "the world is told". */
    static final Set<String> PARTICIPLES = set("made", "done", "seen", "told", "known", "given", "taken", "shown", "grown", "born",
            "built", "held", "kept", "left", "lost", "brought", "thought", "found", "used", "spread", "set", "put", "cut", "led",
            "paid", "sold", "sent", "spent", "won", "worn", "written", "driven", "chosen", "broken", "spoken", "hidden", "felt");

    /** Words a noun phrase may follow and still start cleanly. */
    private static final Set<String> OPENERS = set(",", ":", "(", ";", "\"", "“", "the", "a", "an", "this", "these", "those",
            "other", "many", "some", "several", "most", "its", "their", "our", "his", "her", "all", "each", "every", "both");

    /**
     * Whether the phrase at start begins cleanly: at the sentence's start, or
     * after an article or a comma. After a preposition or "and" it's the tail
     * of a bigger phrase ("a person from the US or Europe is called") and the
     * rules can't tell what the whole thing is, so they don't guess.
     */
    private static boolean cleanStart(List<String> q, int start) {
        if (start == 0) return true;
        String p = q.get(start - 1);
        if (!OPENERS.contains(p)) return false;
        if (ARTICLES.contains(p) && start >= 2 && PREPOSITIONS.contains(q.get(start - 2))) return false;  // "times of the year": a complement
        return !(ARTICLES.contains(p) || "this".equals(p) || "these".equals(p)) || start < 2 || !q.get(start - 2).endsWith("ing");  // "Researching this argument is"
    }

    /** What a title before a name ends in: "the primatologist Carel van Schaik", "the 1994 film True Lies". */
    private static boolean titleHead(String t) {
        return t.endsWith("ist") || t.endsWith("er") || t.endsWith("or") || t.endsWith("ian") || t.endsWith("ic")
                || set("king", "queen", "chief", "leader", "poet", "film", "book", "song", "novel", "album", "president",
                       "minister", "emperor", "pope", "saint", "general", "judge", "scholar", "anthropologist").contains(t);
    }

    private static final Set<String> NUMBER_WORDS = set("one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "dozen", "hundred", "thousand", "million", "billion", "first", "second", "third", "last");

    /** Like subjectStart, but a kind stops at "of": "the distribution of human traits such as" → human traits. */
    private static int kindStart(List<String> q, int end) {
        int s = end;
        while (s > 0 && end - s < 4) {
            String t = q.get(s - 1);
            if (!Character.isLetterOrDigit(t.charAt(0)) || SUBJECT_STOP.contains(t) || COPULAS.contains(t) || CLAUSE.contains(t)
                    || PREPOSITIONS.contains(t) || t.endsWith("ing") && t.length() > 4) break;   // "featuring dishes such as": not featuring
            s--;
        }
        return s;
    }

    /** Where the noun phrase ending just before end starts: walks back over words, at most five. */
    private static int subjectStart(List<String> q, int end) {
        int s = end;
        while (s > 0 && end - s < 5) {
            String t = q.get(s - 1);
            if (!Character.isLetterOrDigit(t.charAt(0)) || SUBJECT_STOP.contains(t) || COPULAS.contains(t) || CLAUSE.contains(t)
                    || PREPOSITIONS.contains(t) && !"of".equals(t)) break;
            s--;
        }
        return s;
    }

    /** Where the noun phrase starting at start ends: at a phrase boundary, through "of", at most five words. */
    private static int phraseEnd(List<String> q, int start) {
        int e = start;
        // (initials: "Henry M. Stanley" runs on over "M.")
        while (e < q.size() && content(q, start, e) < CONCEPT_WORDS && Character.isLetterOrDigit(q.get(e).charAt(0)) && !CLAUSE.contains(q.get(e))
                && !(e > start && (PREPOSITIONS.contains(q.get(e)) && !"of".equals(q.get(e)) || PARTICIPLES.contains(q.get(e))
                        || q.get(e).endsWith("ed") && q.get(e).length() > 4))
                && (!endsPhrase(q, e) || "of".equals(q.get(e)) && e > start && e + 1 < q.size() && Character.isLetterOrDigit(q.get(e + 1).charAt(0)) && !endsPhrase(q, e + 1)
                    || q.get(e).endsWith("ing") && e > start && needsNoun(q.get(e - 1))))   // "intentional chopping": the -ing word is the noun
            e++;
        return e;
    }

    private static int content(List<String> q, int from, int to) {
        int c = 0;
        for (int i = from; i < to; i++) if (!"of".equals(q.get(i)) && !"and".equals(q.get(i)) && !ARTICLES.contains(q.get(i))) c++;
        return c;
    }

    /** An adjective that can't end a noun phrase: "evidence of intentional". */
    private static boolean needsNoun(String t) {
        return t.length() > 4 && (t.endsWith("al") || t.endsWith("ous") || t.endsWith("ive") || t.endsWith("ic") || t.endsWith("ful"));
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
