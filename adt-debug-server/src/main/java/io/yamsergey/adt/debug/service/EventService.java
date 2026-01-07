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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Service for managing debug events.
 *
 * <p>Periodically pulls events from the device using ADB and caches them
 * for serving via the REST API.</p>
 */
@Service
@EnableScheduling
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    // These are retrieved from static holders set before context starts
    private String deviceSerial;
    private String packageName;

    private final List<DebugEvent> events = new CopyOnWriteArrayList<>();
    private final BinaryEventReader reader = new BinaryEventReader();
    private Path tempEventFile;
    private long lastPullTime = 0;

    @PostConstruct
    public void init() throws IOException {
        // Get values from static holders set before context starts
        this.deviceSerial = DebugServerApplication.getDeviceSerial();
        this.packageName = DebugServerApplication.getPackageName();

        tempEventFile = Files.createTempFile("adt-events-", ".bin");
        tempEventFile.toFile().deleteOnExit();
        log.info("EventService initialized for package '{}', temp file: {}", packageName, tempEventFile);
    }

    /**
     * Periodically pulls events from the device.
     */
    @Scheduled(fixedRate = 1000) // Every second
    public void pullEvents() {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }

        try {
            String remotePath = "/data/data/" + packageName + "/cache/events.bin";
            boolean pulled = pullFile(remotePath, tempEventFile.toFile());

            if (pulled) {
                List<DebugEvent> newEvents = reader.readEvents(tempEventFile.toFile());
                if (newEvents.size() > events.size()) {
                    // Add new events
                    for (int i = events.size(); i < newEvents.size(); i++) {
                        events.add(newEvents.get(i));
                    }
                    log.debug("Pulled {} new events (total: {})", newEvents.size() - events.size(), events.size());
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
