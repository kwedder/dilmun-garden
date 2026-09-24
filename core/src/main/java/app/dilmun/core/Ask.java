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

    /** One fact, readable, numbered for citation. */
    public static String line(int n, Map<String, Object> f) {
        long support = f.get("support") instanceof Number ? ((Number) f.get("support")).longValue() : 1;
        return "[" + n + "] " + f.get("entity") + " " + String.valueOf(f.get("a")).replace('_', ' ') + " " + f.get("v")
                + (support >= 2 ? " (" + support + " sources)" : " (approved)");
    }

    public static String system(List<Object> facts) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are Dilmun, a memory that lives on this device. Answer the user's question.\n");
        if (facts.isEmpty()) {
            sb.append("The memory holds no facts about this question. Say so in one short sentence, then answer from general knowledge and say that part is not from memory.");
        } else {
            sb.append("These facts are from the user's settled memory; each one passed a promotion gate. ")
              .append("When a fact supports your answer, cite it by number, like [1]. ")
              .append("If the facts don't cover the question, say so, then answer from general knowledge and say that part is not from memory. Be brief.\n\nFacts:\n");
            for (int i = 0; i < facts.size(); i++) {
                @SuppressWarnings("unchecked") Map<String, Object> f = (Map<String, Object>) facts.get(i);
                sb.append(line(i + 1, f)).append('\n');
            }
        }
        return sb.toString();
    }

    /** history: earlier {role, content} turns, oldest first. */
    public static List<String[]> messages(List<String[]> history, String question, List<Object> facts, boolean useMemory) {
        List<String[]> out = new ArrayList<>();
        if (useMemory) out.add(new String[]{"system", system(facts)});
        else out.add(new String[]{"system", "You are a helpful assistant running on this device. Be brief."});
        int from = Math.max(0, history.size() - HISTORY);
        for (int i = from; i < history.size(); i++) out.add(history.get(i));
        out.add(new String[]{"user", question});
        return out;
    }
}
