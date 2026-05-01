package io.yamsergey.dta.sidekick.interceptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, monotonically-timestamped ring buffer for the network
 * interceptor's {@code log()} output and caught script errors.
 *
 * <p>Each entry carries a strictly-increasing sequence number so MCP
 * can poll {@code GET /interceptor/logs?since=&lt;seq&gt;} and only
 * receive what's new. The sequence counter is process-global; callers
 * never reset it — wrap-around is impractical at one entry per
 * 9.2×10¹⁸ calls.</p>
 *
 * <p>Capped at {@value #MAX_ENTRIES} entries; the oldest is dropped
 * silently when full. We do not bound individual entry size — the
 * agent's script controls what goes into {@code log()}, and the user
 * has explicitly opted out of paternalistic caps for this dev tool
 * (see {@code feedback_dev_tool_no_kid_gloves} memory). Total
 * memory ceiling is whatever the script authors choose to put in.</p>
 */
public final class ScriptLog {

    /** Capacity in entries. ~1k is plenty for an iteration cycle. */
    private static final int MAX_ENTRIES = 1024;

    /** Process-global sequence — never reset. */
    private static final AtomicLong SEQ = new AtomicLong(0);

    public enum Level { LOG, ERROR }

    public static final class Entry {
        public final long seq;
        public final long timestampMs;
        public final Level level;
        public final String text;
        public Entry(long seq, long timestampMs, Level level, String text) {
            this.seq = seq;
            this.timestampMs = timestampMs;
            this.level = level;
            this.text = text;
        }
        public long seq() { return seq; }
        public long timestampMs() { return timestampMs; }
        public Level level() { return level; }
        public String text() { return text; }
    }

    private final Deque<Entry> entries = new ArrayDeque<>(MAX_ENTRIES);

    public synchronized void append(Level level, String text) {
        if (entries.size() >= MAX_ENTRIES) {
            entries.removeFirst();
        }
        entries.addLast(new Entry(
                SEQ.incrementAndGet(),
                System.currentTimeMillis(),
                level,
                text == null ? "" : text));
    }

    public void log(String text) {
        append(Level.LOG, text);
    }

    public void error(String text) {
        append(Level.ERROR, text);
    }

    /**
     * Returns entries strictly newer than {@code sinceSeq} in original
     * order. Pass {@code 0} to get everything currently buffered.
     */
    public synchronized List<Entry> since(long sinceSeq) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.seq() > sinceSeq) out.add(e);
        }
        return out;
    }

    public synchronized void clear() {
        entries.clear();
    }
}
