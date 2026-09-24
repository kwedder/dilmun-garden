package app.dilmun.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extract from many sources, one after another.
 *
 * Each source gets its own directive, issued only when its turn comes, and its
 * own signed result: a queue of 150 files never meets the open-directive cap or
 * a directive's ten-minute expiry, and a refusal in one source never touches
 * another. Each source runs as a separate job on the caller's worker, so other
 * work (a question on the Ask tab) can take its turn between two sources.
 *
 * Pause and cancel take effect between sources: the source being read finishes
 * and commits cleanly first. The queue is saved after every source, so a run
 * the app didn't finish can be resumed where it stopped.
 */
public final class BulkRun {

    /** Where each step runs: the app's single worker thread. */
    public interface Worker { void submit(Runnable step); }

    /** The agent for one source (by display name). It should report passages to {@code progress}. */
    public interface Agents { Agent make(String sourceName, ModelAgent.Progress progress); }

    /** The run moved on: repaint. Called on the worker thread. */
    public interface Listener { void changed(Map<String, Object> status); }

    /** Persist the queue ({@code null} when there is nothing left to resume). */
    public interface Saver { void save(Map<String, Object> saved); }

    /** Pause after this many sources fail in a row: something is wrong beyond one file. */
    static final int MAX_FAILS_IN_A_ROW = 3;
    static final int KEEP_ERRORS = 20;

    private final Engine engine;
    private final Worker worker;
    private final Listener listener;
    private final Saver saver;
    private Engine.Sources sources;
    private Agents agents;

    private List<String> ids = new ArrayList<>(), names = new ArrayList<>();
    private int next = 0;
    private String state = "idle";            // idle | running | paused | interrupted | cancelled | done
    private String why = null;                // why it paused on its own
    private boolean skip = true, busy = false;
    private String current = null;
    private long passage = 0, passages = 0;
    private long extracted = 0, skipped = 0, failed = 0, kept = 0, refused = 0, failsInARow = 0;
    private long started = 0;
    private final List<Object> errors = new ArrayList<>();

    public BulkRun(Engine engine, Worker worker, Listener listener, Saver saver) {
        this.engine = engine;
        this.worker = worker;
        this.listener = listener;
        this.saver = saver;
    }

    /** Start a run over these sources, in this order. Names are for display. */
    public synchronized void start(List<String> sourceIds, List<String> sourceNames, boolean skipExtracted,
                                   Engine.Sources src, Agents make) {
        if (isActive()) throw new Store.Rejected("a bulk run is already going: pause or cancel it first");
        if (sourceIds.isEmpty()) throw new Store.Rejected("no sources selected");
        ids = new ArrayList<>(sourceIds);
        names = new ArrayList<>(sourceNames.size() == sourceIds.size() ? sourceNames : sourceIds);
        sources = src;
        agents = make;
        skip = skipExtracted;
        next = 0;
        extracted = skipped = failed = kept = refused = failsInARow = 0;
        errors.clear();
        why = null;
        started = System.currentTimeMillis();
        state = "running";
        engine.noteWork("bulk", "Bulk run: " + plural(ids.size(), "source", "sources") + " queued" + (skip ? ", skipping any already extracted" : ""),
                Tx.m("event", "start", "total", (long) ids.size()));
        changed();
        kick();
    }

    /** Stop after the source being read now. */
    public synchronized void pause() {
        if (!"running".equals(state)) return;
        state = "paused";
        why = null;
        engine.noteWork("bulk", "Bulk run: pausing after " + (current != null ? current : "this source"), Tx.m("event", "pause"));
        changed();
    }

    /** Carry on from the next source. A restored run needs the sources and agent again. */
    public synchronized void resume(Engine.Sources src, Agents make) {
        if (!"paused".equals(state) && !"interrupted".equals(state)) return;
        if (src != null) sources = src;
        if (make != null) agents = make;
        if (sources == null || agents == null) throw new Store.Rejected("no sources to resume with");
        state = "running";
        why = null;
        failsInARow = 0;
        engine.noteWork("bulk", "Bulk run: resuming at source " + (next + 1) + " of " + ids.size(), Tx.m("event", "resume", "at", (long) next + 1));
        changed();
        kick();
    }

    /** Stop for good after the source being read now. What was committed stays committed. */
    public synchronized void cancel() {
        if ("idle".equals(state) || "done".equals(state) || "cancelled".equals(state)) return;
        boolean wasBusy = busy;
        state = "cancelled";
        engine.noteWork("bulk", "Bulk run: cancelled" + (wasBusy ? " after " + current : "") + " · " + extracted + " extracted, " + skipped + " skipped",
                Tx.m("event", "cancel"));
        save();
        changed();
    }

    /** Forget a finished, cancelled or interrupted run. */
    public synchronized void clear() {
        if ("running".equals(state) || busy) throw new Store.Rejected("the run is still going: cancel it first");
        state = "idle";
        ids = new ArrayList<>();
        names = new ArrayList<>();
        errors.clear();
        changed();
    }

    /** A run the app didn't finish last time: shown as interrupted, ready to resume. */
    @SuppressWarnings("unchecked")
    public synchronized void restore(Map<String, Object> saved) {
        if (saved == null || isActive()) return;
        List<Object> si = (List<Object>) saved.get("ids"), sn = (List<Object>) saved.get("names");
        if (si == null || si.isEmpty()) return;
        ids = new ArrayList<>();
        names = new ArrayList<>();
        for (Object o : si) ids.add(String.valueOf(o));
        for (Object o : sn != null && sn.size() == si.size() ? sn : si) names.add(String.valueOf(o));
        next = (int) num(saved.get("next"));
        if (next >= ids.size()) { ids.clear(); names.clear(); save(); return; }
        skip = !Boolean.FALSE.equals(saved.get("skip"));
        extracted = num(saved.get("extracted"));
        skipped = num(saved.get("skipped"));
        failed = num(saved.get("failed"));
        kept = num(saved.get("kept"));
        refused = num(saved.get("refused"));
        started = num(saved.get("started"));
        state = "interrupted";
        why = "the app closed during the run";
    }

    public synchronized boolean isActive() {
        return "running".equals(state) || "paused".equals(state) || "interrupted".equals(state) || busy;
    }

    public synchronized boolean isRunning() { return "running".equals(state) || busy; }

    public synchronized Map<String, Object> status() {
        return Tx.m("state", state, "why", why, "total", (long) ids.size(), "next", (long) next,
                "current", current, "passage", passage, "passages", passages,
                "extracted", extracted, "skipped", skipped, "failed", failed, "kept", kept, "refused", refused,
                "busy", busy, "skip", skip, "started", started, "errors", new ArrayList<Object>(errors));
    }

    // ------------------------------------------------------------ the queue

    private void kick() { if (!busy) worker.submit(this::step); }

    private void step() {
        final String id, name;
        final Engine.Sources src;
        final Agents make;
        final int index;
        synchronized (this) {
            if (busy || !"running".equals(state)) return;
            if (next >= ids.size()) {
                state = "done";
                current = null;
                engine.noteWork("bulk", "Bulk run finished: " + extracted + " extracted, " + skipped + " skipped, " + failed + " failed · "
                        + kept + " facts kept, " + refused + " refused", Tx.m("event", "done"));
                save();
                changed();
                return;
            }
            index = next;
            id = ids.get(index);
            name = names.get(index);
            src = sources;
            make = agents;
            busy = true;
            current = name;
            passage = passages = 0;
            changed();
        }
        String outcome = null, error = null;
        long k = 0, r = 0;
        try {
            if (skip && engine.extracted(id)) {
                outcome = "skipped";
                engine.noteWork("bulk", "Bulk run: skipped " + name + ", already extracted", Tx.m("event", "skip", "i", (long) index + 1, "total", (long) ids.size()));
            } else {
                engine.noteWork("bulk", "Bulk run: source " + (index + 1) + " of " + ids.size() + " · " + name,
                        Tx.m("event", "source", "i", (long) index + 1, "total", (long) ids.size()));
                Agent agent = make.make(name, new ModelAgent.Progress() {
                    @Override public void passage(int i, int n) { onPassage(i, n); }
                    @Override public void text(String piece) { }
                });
                Map<String, Object> res = engine.extract(id, src, agent);
                k = num(res.get("accepted"));
                Object rej = res.get("rejected");
                r = rej instanceof List ? ((List<?>) rej).size() : 0;
                outcome = "extracted";
            }
        } catch (Store.Rejected e) {
            error = e.getMessage();
        } catch (RuntimeException e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } catch (OutOfMemoryError e) {
            error = "the phone ran out of memory";
        }
        synchronized (this) {
            busy = false;
            next = index + 1;
            if ("skipped".equals(outcome)) skipped++;
            else if ("extracted".equals(outcome)) { extracted++; kept += k; refused += r; failsInARow = 0; }
            if (error != null) {
                failed++;
                failsInARow++;
                errors.add(Tx.m("source", name, "error", error));
                while (errors.size() > KEEP_ERRORS) errors.remove(0);
                if ("running".equals(state) && (error.startsWith("paused by the steward") || error.startsWith("frozen"))) {
                    state = "paused";
                    why = error;
                } else if ("running".equals(state) && failsInARow >= MAX_FAILS_IN_A_ROW) {
                    state = "paused";
                    why = plural(failsInARow, "source", "sources") + " failed in a row; the last: " + error;
                }
                if (why != null && "paused".equals(state))
                    engine.noteWork("bulk", "Bulk run paused: " + why, Tx.m("event", "pause"));
            }
            current = null;
            passage = passages = 0;
            save();
            changed();
            if ("running".equals(state)) worker.submit(this::step);
        }
    }

    private synchronized void onPassage(int i, int n) {
        passage = i;
        passages = n;
        changed();
    }

    private void changed() {
        if (listener != null) listener.changed(status());
    }

    private void save() {
        if (saver == null) return;
        boolean resumable = ("running".equals(state) || "paused".equals(state) || "interrupted".equals(state)) && next < ids.size();
        if (!resumable) { saver.save(null); return; }
        saver.save(Tx.m("ids", new ArrayList<Object>(ids), "names", new ArrayList<Object>(names), "next", (long) next,
                "skip", skip, "extracted", extracted, "skipped", skipped, "failed", failed,
                "kept", kept, "refused", refused, "started", started));
    }

    private static long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0L; }

    private static String plural(long n, String one, String many) { return n + " " + (n == 1 ? one : many); }

    /** For tests and the screens: the ids in order. */
    public synchronized List<String> queue() { return new ArrayList<>(ids); }
}
