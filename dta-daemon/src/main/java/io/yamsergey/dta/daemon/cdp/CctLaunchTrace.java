package io.yamsergey.dta.daemon.cdp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded ring of recent Custom Tab launches with per-step timing for triage.
 *
 * <p>The CCT path goes through several async hops (sidekick JVMTI hook,
 * SSE event, daemon ACK, Chrome-side polling, CDP attach, navigate).
 * When a launch ends up "stuck on blank" or otherwise misbehaves, the
 * after-the-fact log doesn't show <em>where</em> the chain broke. This
 * structure keeps the last {@link #CAPACITY} launches with the full step
 * timeline so we can grep one launch end-to-end and snapshot Chrome's
 * targets at the point of failure.</p>
 *
 * <p>Keyed by {@code eventId} (UUID minted by sidekick on every launchUrl).
 * Same eventId travels through SSE, the daemon's poller, and the ACK back
 * to sidekick — so a single trace ties together logs on both sides.</p>
 *
 * <p>Thread-safe: writes happen on the cdp-attach worker thread;
 * reads happen on Javalin handler threads.</p>
 */
public final class CctLaunchTrace {

    private static final int CAPACITY = 50;

    private static final CctLaunchTrace INSTANCE = new CctLaunchTrace();

    public static CctLaunchTrace getInstance() {
        return INSTANCE;
    }

    private final Deque<Entry> entries = new ConcurrentLinkedDeque<>();
    private final Map<String, Entry> byEventId = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(0);

    private CctLaunchTrace() {}

    /**
     * Begins a new trace entry. Drops the oldest entry once {@link #CAPACITY}
     * is reached.
     *
     * @param eventType which SSE event triggered this — typically
     *                  {@code "customtab_will_launch"} or
     *                  {@code "chrome_will_launch"}. Both flows share the
     *                  same daemon-side handler but the originating event
     *                  matters for triage.
     */
    public Entry begin(String eventType, String eventId, String packageName,
                       String deviceSerial, String targetUrl) {
        Entry entry = new Entry(sequence.incrementAndGet(), eventType, eventId,
                packageName, deviceSerial, targetUrl);
        byEventId.put(eventId, entry);
        entries.addLast(entry);
        while (entries.size() > CAPACITY) {
            Entry removed = entries.pollFirst();
            if (removed != null) {
                byEventId.remove(removed.eventId, removed);
            }
        }
        return entry;
    }

    /**
     * Returns the entry for the given eventId, or null if it's been evicted.
     */
    public Entry get(String eventId) {
        if (eventId == null) return null;
        return byEventId.get(eventId);
    }

    /**
     * Returns all entries with seq strictly greater than {@code since},
     * in chronological order (oldest first).
     */
    public List<Entry> snapshot(long since) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.seq > since) {
                out.add(e);
            }
        }
        return out;
    }

    /** Latest entry, or null if buffer is empty. */
    public Entry latest() {
        return entries.peekLast();
    }

    /**
     * One step within a launch trace.
     */
    public static final class Step {
        public final String name;
        public final long ts;
        public final long durationMs;
        public final boolean ok;
        public final String error;
        public final Map<String, Object> details;

        Step(String name, long ts, long durationMs, boolean ok, String error, Map<String, Object> details) {
            this.name = name;
            this.ts = ts;
            this.durationMs = durationMs;
            this.ok = ok;
            this.error = error;
            this.details = details;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("ts", ts);
            m.put("durationMs", durationMs);
            m.put("ok", ok);
            if (error != null) m.put("error", error);
            if (details != null && !details.isEmpty()) m.put("details", details);
            return m;
        }
    }

    /**
     * One trace per launch. Mutable through the {@code step*} methods —
     * which are called from a single thread (the cdp-attach worker)
     * during the launch — but reads from other threads see a consistent
     * snapshot via the synchronized {@link #toMap}.
     */
    public static final class Entry {
        public final long seq;
        public final String eventType;
        public final String eventId;
        public final String packageName;
        public final String deviceSerial;
        public final String targetUrl;
        public final long startedAt;

        private final List<Step> steps = Collections.synchronizedList(new ArrayList<>());
        private volatile String finalState = "in_progress";
        private volatile Long endedAt;
        private volatile List<Map<String, Object>> chromeTargetsAtFailure;
        private volatile String wsState;
        private volatile long lastStepStart;

        Entry(long seq, String eventType, String eventId, String packageName,
              String deviceSerial, String targetUrl) {
            this.seq = seq;
            this.eventType = eventType;
            this.eventId = eventId;
            this.packageName = packageName;
            this.deviceSerial = deviceSerial;
            this.targetUrl = targetUrl;
            this.startedAt = System.currentTimeMillis();
            this.lastStepStart = startedAt;
        }

        /** Records a step with a duration measured from the previous step. */
        public Step step(String name) {
            return step(name, true, null, null);
        }

        public Step step(String name, Map<String, Object> details) {
            return step(name, true, null, details);
        }

        public Step stepFailed(String name, String error) {
            return step(name, false, error, null);
        }

        public Step stepFailed(String name, String error, Map<String, Object> details) {
            return step(name, false, error, details);
        }

        private Step step(String name, boolean ok, String error, Map<String, Object> details) {
            long now = System.currentTimeMillis();
            long dur = now - lastStepStart;
            lastStepStart = now;
            Step s = new Step(name, now, dur, ok, error, details);
            steps.add(s);
            return s;
        }

        /** Marks the trace done with a final state. */
        public void finish(String state) {
            this.finalState = state;
            this.endedAt = System.currentTimeMillis();
        }

        public void recordChromeTargets(List<Map<String, Object>> targets) {
            this.chromeTargetsAtFailure = targets;
        }

        public void recordWsState(String state) {
            this.wsState = state;
        }

        public boolean hasStep(String name) {
            synchronized (steps) {
                for (Step s : steps) {
                    if (name.equals(s.name)) return true;
                }
            }
            return false;
        }

        public String finalState() {
            return finalState;
        }

        public synchronized Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", seq);
            m.put("eventType", eventType);
            m.put("eventId", eventId);
            m.put("packageName", packageName);
            m.put("deviceSerial", deviceSerial);
            m.put("targetUrl", targetUrl);
            m.put("startedAt", startedAt);
            if (endedAt != null) m.put("endedAt", endedAt);
            m.put("finalState", finalState);
            List<Map<String, Object>> stepMaps = new ArrayList<>();
            synchronized (steps) {
                for (Step s : steps) stepMaps.add(s.toMap());
            }
            m.put("steps", stepMaps);
            if (chromeTargetsAtFailure != null) m.put("chromeTargetsAtFailure", chromeTargetsAtFailure);
            if (wsState != null) m.put("wsState", wsState);
            return m;
        }
    }
}
