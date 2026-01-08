package io.yamsergey.adt.debug.service;

import io.yamsergey.adt.debug.DebugServerApplication;
import io.yamsergey.adt.debug.binary.BinaryEventReader;
import io.yamsergey.adt.debug.model.DebugEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Service for managing debug events.
 *
 * <p>Uses a hybrid approach for receiving events:</p>
 * <ul>
 *   <li><b>Streaming (real-time):</b> SSE connection for immediate updates</li>
 *   <li><b>Polling (fallback):</b> Periodic file pull for reliability</li>
 * </ul>
 */
@Service
@EnableScheduling
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    // These are retrieved from static holders set before context starts
    private String deviceSerial;
    private String packageName;

    private final List<DebugEvent> events = new CopyOnWriteArrayList<>();
    private final Set<String> seenEventIds = ConcurrentHashMap.newKeySet();
    // Map transaction ID (data.id) -> event for merging request/response
    private final Map<String, DebugEvent> eventsByTransactionId = new ConcurrentHashMap<>();
    private final BinaryEventReader reader = new BinaryEventReader();
    private final StreamService streamService;

    private Path tempEventFile;
    private long lastPullTime = 0;

    public EventService(StreamService streamService) {
        this.streamService = streamService;
    }

    @PostConstruct
    public void init() throws IOException {
        // Get values from static holders set before context starts
        this.deviceSerial = DebugServerApplication.getDeviceSerial();
        this.packageName = DebugServerApplication.getPackageName();

        tempEventFile = Files.createTempFile("adt-events-", ".bin");
        tempEventFile.toFile().deleteOnExit();

        // Setup streaming with event consumer
        streamService.setEventConsumer(this::onStreamedEvent);
        streamService.start();

        log.info("EventService initialized for package '{}' (streaming + polling)", packageName);
    }

    /**
     * Handles events received via SSE streaming.
     * Merges events with the same transaction ID (data.id).
     */
    private void onStreamedEvent(DebugEvent event) {
        if (event == null || event.getId() == null) {
            return;
        }

        addOrMergeEvent(event);
    }

    /**
     * Adds a new event or merges it with an existing one based on transaction ID.
     */
    @SuppressWarnings("unchecked")
    private void addOrMergeEvent(DebugEvent event) {
        // Get transaction ID from data.id (different from event.id)
        String transactionId = getTransactionId(event);

        if (transactionId != null) {
            DebugEvent existing = eventsByTransactionId.get(transactionId);
            if (existing != null) {
                // Merge new data into existing event
                mergeEventData(existing, event);
                log.debug("Merged event for transaction: {}", transactionId);
                return;
            }
            // New transaction - track it
            eventsByTransactionId.put(transactionId, event);
        }

        // Add as new event if not seen before
        if (seenEventIds.add(event.getId())) {
            events.add(event);
            log.debug("Event added: {} (total: {})", event.getId(), events.size());
        }
    }

    /**
     * Gets the transaction ID from event data.
     */
    private String getTransactionId(DebugEvent event) {
        Map<String, Object> data = event.getData();
        if (data == null) return null;
        Object id = data.get("id");
        return id != null ? String.valueOf(id) : null;
    }

    /**
     * Merges data from a new event into an existing event.
     * Updates status, response fields, duration, etc.
     */
    @SuppressWarnings("unchecked")
    private void mergeEventData(DebugEvent existing, DebugEvent update) {
        Map<String, Object> existingData = existing.getData();
        Map<String, Object> updateData = update.getData();

        if (existingData == null || updateData == null) return;

        // Update fields that may have changed (response data, status, duration)
        for (String key : updateData.keySet()) {
            Object newValue = updateData.get(key);
            Object oldValue = existingData.get(key);

            // Prefer non-null, non-zero, non-empty values
            if (shouldUpdateValue(oldValue, newValue)) {
                existingData.put(key, newValue);
            }
        }

        // Update timestamp if the new event is newer
        if (update.getTimestamp() > existing.getTimestamp()) {
            existing.setTimestamp(update.getTimestamp());
        }
    }

    /**
     * Determines if a value should be updated.
     */
    private boolean shouldUpdateValue(Object oldValue, Object newValue) {
        if (newValue == null) return false;
        if (oldValue == null) return true;

        // Prefer non-zero numbers
        if (newValue instanceof Number && oldValue instanceof Number) {
            double newNum = ((Number) newValue).doubleValue();
            double oldNum = ((Number) oldValue).doubleValue();
            return newNum != 0 && (oldNum == 0 || newNum > oldNum);
        }

        // Prefer non-empty strings
        if (newValue instanceof String && oldValue instanceof String) {
            String newStr = (String) newValue;
            String oldStr = (String) oldValue;
            // Update status fields and non-empty strings
            return !newStr.isEmpty() && (oldStr.isEmpty() ||
                   (newStr.equals("COMPLETED") || newStr.equals("FAILED")));
        }

        // For maps/lists, prefer non-empty
        if (newValue instanceof Map && ((Map<?,?>) newValue).isEmpty()) return false;
        if (newValue instanceof List && ((List<?>) newValue).isEmpty()) return false;

        return true;
    }

    /**
     * Returns whether the streaming connection is active.
     */
    public boolean isStreamingConnected() {
        return streamService.isConnected();
    }

    /**
     * Periodically pulls events from the device (fallback when streaming is unavailable).
     */
    @Scheduled(fixedRate = 2000) // Every 2 seconds (reduced frequency since streaming handles real-time)
    public void pullEvents() {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }

        try {
            String remotePath = "/data/data/" + packageName + "/cache/events.bin";
            boolean pulled = pullFile(remotePath, tempEventFile.toFile());

            if (pulled) {
                List<DebugEvent> newEvents = reader.readEvents(tempEventFile.toFile());
                int before = events.size();

                for (DebugEvent event : newEvents) {
                    // Use merge logic to handle start/complete events
                    addOrMergeEvent(event);
                }

                int added = events.size() - before;
                if (added > 0) {
                    log.debug("Pulled {} new events (total: {})", added, events.size());
                }
                lastPullTime = System.currentTimeMillis();
            }
        } catch (Exception e) {
            log.warn("Failed to pull events: {}", e.getMessage());
        }
    }

    /**
     * Forces an immediate pull of events.
     */
    public void forcePull() {
        pullEvents();
    }

    /**
     * Returns all cached events.
     */
    public List<DebugEvent> getAllEvents() {
        return Collections.unmodifiableList(events);
    }

    /**
     * Returns events filtered by type.
     */
    public List<DebugEvent> getEventsByType(String type) {
        return events.stream()
                .filter(e -> type.equals(e.getType()))
                .collect(Collectors.toList());
    }

    /**
     * Returns a single event by ID.
     */
    public DebugEvent getEvent(String id) {
        return events.stream()
                .filter(e -> id.equals(e.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Clears all cached events and the device event file.
     */
    public void clearEvents() {
        events.clear();
        seenEventIds.clear();
        eventsByTransactionId.clear();

        if (packageName != null && !packageName.isEmpty()) {
            try {
                String remotePath = "/data/data/" + packageName + "/cache/events.bin";
                runAdb("shell", "run-as", packageName, "rm", "-f", remotePath);
                log.info("Cleared events on device");
            } catch (Exception e) {
                log.warn("Failed to clear device events: {}", e.getMessage());
            }
        }
    }

    /**
     * Returns the number of cached events.
     */
    public int getEventCount() {
        return events.size();
    }

    /**
     * Returns the last pull time.
     */
    public long getLastPullTime() {
        return lastPullTime;
    }

    private boolean pullFile(String remotePath, File localFile) throws IOException {
        // Use run-as to access the app's private directory
        List<String> args = new ArrayList<>();
        args.add("adb");
        if (deviceSerial != null && !deviceSerial.isEmpty()) {
            args.add("-s");
            args.add(deviceSerial);
        }
        args.add("exec-out");
        args.add("run-as");
        args.add(packageName);
        args.add("cat");
        args.add(remotePath);

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectOutput(localFile);
        Process process = pb.start();

        try {
            int exitCode = process.waitFor();
            return exitCode == 0 && localFile.length() > 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String runAdb(String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("adb");
        if (deviceSerial != null && !deviceSerial.isEmpty()) {
            command.add("-s");
            command.add(deviceSerial);
        }
        Collections.addAll(command, args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();
            return output.toString().trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
