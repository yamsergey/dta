package io.yamsergey.dta.sidekick.customtabs;

import io.yamsergey.dta.sidekick.SidekickLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Inspector for Chrome Custom Tabs events.
 *
 * <p>Tracks Custom Tab launch events and notifies listeners when
 * the app opens a URL in Chrome Custom Tabs.</p>
 */
public final class CustomTabsInspector {

    private static final String TAG = "CustomTabsInspector";
    private static final int MAX_EVENTS = 100;

    private static final List<CustomTabEvent> events = new CopyOnWriteArrayList<>();
    private static final List<CustomTabEventListener> listeners = new CopyOnWriteArrayList<>();

    // Prevent instantiation
    private CustomTabsInspector() {}

    /**
     * Records a Custom Tab launch event.
     *
     * @param event the event to record
     */
    public static void recordEvent(CustomTabEvent event) {
        if (event == null) {
            return;
        }

        // Add event to history
        events.add(event);

        // Limit event history
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }

        SidekickLog.i(TAG, "Custom Tab opened: " + event.getUrl());

        // Notify listeners
        for (CustomTabEventListener listener : listeners) {
            try {
                listener.onCustomTabOpened(event);
            } catch (Throwable t) {
                SidekickLog.e(TAG, "Error notifying listener", t);
            }
        }
    }

    /**
     * Returns all recorded Custom Tab events.
     */
    public static List<CustomTabEvent> getEvents() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    /**
     * Returns the most recent Custom Tab event, or null if none.
     */
    public static CustomTabEvent getLatestEvent() {
        if (events.isEmpty()) {
            return null;
        }
        return events.get(events.size() - 1);
    }

    /**
     * Clears all recorded events.
     */
    public static void clearEvents() {
        events.clear();
        SidekickLog.i(TAG, "Custom Tab events cleared");
    }

    /**
     * Returns the number of recorded events.
     */
    public static int getEventCount() {
        return events.size();
    }

    /**
     * Adds a listener for Custom Tab events.
     */
    public static void addListener(CustomTabEventListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a Custom Tab event listener.
     */
    public static void removeListener(CustomTabEventListener listener) {
        listeners.remove(listener);
    }

    /**
     * Listener interface for Custom Tab events.
     */
    public interface CustomTabEventListener {
        /**
         * Called when a Custom Tab is opened.
         *
         * @param event the Custom Tab event
         */
        void onCustomTabOpened(CustomTabEvent event);
    }
}
