package app.dilmun.core;

import java.util.ArrayList;
import java.util.List;

/**
 * An agent the arbiters delegate a check to: one question, one word back.
 * It judges only; the arbiters decide what its answer counts for.
 */
public interface Verifier {
    /** Recorded on every verdict, so you can tell which model said what. */
    String id();

    /** "yes" or "no". Anything else is refused by the arbiters. */
    String judge(String briefing, String question);

    /** A model as a verifier: the briefing, the question, and a grammar that allows only "yes" or "no". */
    final class Model implements Verifier {
        private final Llm llm;
        public Model(Llm llm) { this.llm = llm; }
        @Override public String id() { return llm.id(); }
        @Override public String judge(String briefing, String question) {
            List<String[]> msgs = new ArrayList<>();
            msgs.add(new String[]{"system", briefing});
            msgs.add(new String[]{"user", question});
            return llm.generate(msgs, 3, 0f, false, Briefing.VERIFY_GRAMMAR, null).trim().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
