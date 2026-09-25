package app.dilmun.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the model's prompt for a question. Asking reads memory and never
 * writes to it: nothing the model says here becomes a fact.
 */
public final class Ask {
    private Ask() {}

    /** Earlier turns kept in the prompt, so a 1B model's context isn't spent on old talk. */
    static final int HISTORY = 6;

    /** One fact, readable, numbered for citation, with how sure the memory is of it and its quote. */
    public static String line(int n, Map<String, Object> f) {
        if ("claim".equals(f.get("kind"))) {
            long sup = f.get("support") instanceof Number ? ((Number) f.get("support")).longValue() : 1;
            String how = "settled".equals(f.get("status")) ? plural(sup) : "unconfirmed: " + plural(sup) + ", not yet through the gate";
            String in = f.get("frame") != null ? " (under \"" + f.get("frame") + "\")" : "";
            return "[" + n + "] the source says" + in + ": \"" + f.get("text") + "\" (" + how
                    + (f.get("contested") != null ? "; contested: " + f.get("contested") : "") + ")";
        }
        long support = f.get("support") instanceof Number ? ((Number) f.get("support")).longValue() : 1;
        boolean held = "held".equals(f.get("status"));
        String how = held ? "unconfirmed: " + support + (support == 1 ? " source" : " sources") + ", not yet through the gate"
                : support >= 2 ? support + " sources" : "approved";
        Object q = f.get("quote");
        String quote = q instanceof String && !((String) q).isEmpty() ? " (source says: \"" + q + "\")" : "";
        return "[" + n + "] " + f.get("entity") + " " + String.valueOf(f.get("a")).replace('_', ' ') + " " + f.get("v")
                + " (" + how + (f.get("contested") != null ? "; contested: " + f.get("contested") : "")
                + ("archive".equals(f.get("layer")) ? "; from the archive: its sources are old" : "") + ")" + quote;
    }

    /** The arbiters' reading briefing: how the store is laid out, then the facts. */
    private static String plural(long n) { return n + (n == 1 ? " source" : " sources"); }

    public static String system(List<Object> facts) { return Briefing.read(facts); }

    /**
     * The start of the model's reasoning, written for it. The system prompt alone
     * leaves the facts as a reference a small model can skip; putting them at the
     * top of its own thinking makes it reason from them, fact by fact.
     */
    public static String reasoning(String question, List<Object> facts) {
        StringBuilder sb = new StringBuilder();
        sb.append("The question is: ").append(question.trim()).append('\n');
        if (facts.isEmpty()) {
            return sb.append("My memory holds no facts about this question. I will say so first, and anything I add comes from general knowledge, not from memory.\n").toString();
        }
        sb.append("My memory gives me these facts, and my answer has to be built from them:\n");
        for (int i = 0; i < facts.size(); i++) {
            @SuppressWarnings("unchecked") Map<String, Object> f = (Map<String, Object>) facts.get(i);
            sb.append(line(i + 1, f)).append('\n');
        }
        return sb.append("Going through them one at a time, what each one tells me about the question:\n[1]").toString();
    }

    public static List<String[]> messages(List<String[]> history, String question, List<Object> facts, boolean useMemory) {
        return messages(history, question, facts, useMemory, false);
    }

    /**
     * history: earlier {role, content} turns, oldest first. With memory and think on,
     * the last message is a "prefill": the model's reasoning starts with it.
     */
    public static List<String[]> messages(List<String[]> history, String question, List<Object> facts, boolean useMemory, boolean think) {
        List<String[]> out = new ArrayList<>();
        if (useMemory) out.add(new String[]{"system", system(facts)});
        else out.add(new String[]{"system", "You are a helpful assistant running on this device. Be brief."});
        int from = Math.max(0, history.size() - HISTORY);
        for (int i = from; i < history.size(); i++) out.add(history.get(i));
        out.add(new String[]{"user", question});
        if (useMemory && think) out.add(new String[]{"prefill", reasoning(question, facts)});
        return out;
    }
}
