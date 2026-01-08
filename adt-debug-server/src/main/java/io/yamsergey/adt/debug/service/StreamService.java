package io.yamsergey.adt.debug.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.yamsergey.adt.debug.DebugServerApplication;
import io.yamsergey.adt.debug.model.DebugEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Service for streaming events from the Android device via SSE.
 *
 * <p>Connects to the device's InspectorServer via ADB port forward
 * and receives real-time transaction events.</p>
 */
@Service
public class StreamService {

    private static final Logger log = LoggerFactory.getLogger(StreamService.class);
    private static final int DEVICE_PORT = 8642;
    private static final int RECONNECT_DELAY_MS = 5000;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private Consumer<DebugEvent> eventConsumer;
    private String deviceSerial;
    private int localPort;

    @PostConstruct
    public void init() {
        this.deviceSerial = DebugServerApplication.getDeviceSerial();
        // Use a different local port to avoid conflict with the Spring server
        this.localPort = 18642;
    }

    /**
     * Sets the consumer for received events.
     */
    public void setEventConsumer(Consumer<DebugEvent> consumer) {
        this.eventConsumer = consumer;
    }

    /**
     * Starts the SSE stream connection.
     */
    public void start() {
        if (running.get()) {
            log.warn("Stream service already running");
            return;
        }

        running.set(true);
        executor.submit(this::streamLoop);
        log.info("Stream service started");
    }

    /**
     * Stops the SSE stream connection.
     */
    @PreDestroy
    public void stop() {
        running.set(false);
        connected.set(false);
        executor.shutdownNow();
        log.info("Stream service stopped");
    }

    /**
     * Returns whether the stream is currently connected.
     */
    public boolean isConnected() {
        return connected.get();
    }

    /**
     * Main loop that maintains the SSE connection with auto-reconnect.
     */
    private void streamLoop() {
        while (running.get()) {
            try {
                // Setup ADB port forward
                if (!setupPortForward()) {
                    log.warn("Failed to setup ADB port forward, retrying in {}ms", RECONNECT_DELAY_MS);
                    Thread.sleep(RECONNECT_DELAY_MS);
                    continue;
                }

                // Connect to SSE stream
                connectToStream();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Stream error: {}, reconnecting in {}ms", e.getMessage(), RECONNECT_DELAY_MS);
                connected.set(false);
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Sets up ADB port forwarding.
     */
    private boolean setupPortForward() {
        try {
            ProcessBuilder pb;
            if (deviceSerial != null && !deviceSerial.isEmpty()) {
                pb = new ProcessBuilder("adb", "-s", deviceSerial,
                        "forward", "tcp:" + localPort, "tcp:" + DEVICE_PORT);
            } else {
                pb = new ProcessBuilder("adb",
                        "forward", "tcp:" + localPort, "tcp:" + DEVICE_PORT);
            }

            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                log.debug("ADB port forward established: localhost:{} -> device:{}", localPort, DEVICE_PORT);
                return true;
            } else {
                log.warn("ADB port forward failed with exit code {}", exitCode);
                return false;
            }
        } catch (Exception e) {
            log.warn("Failed to setup ADB port forward: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Connects to the SSE stream and processes events.
     */
    private void connectToStream() throws IOException {
        URL url = new URL("http://localhost:" + localPort + "/events/stream");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Cache-Control", "no-cache");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(0); // No read timeout for streaming

        try {
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log.warn("SSE connection failed with status {}", responseCode);
                return;
            }

            connected.set(true);
            log.info("Connected to SSE stream at localhost:{}", localPort);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {

                String eventType = null;
                StringBuilder dataBuilder = new StringBuilder();

                String line;
                while (running.get() && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        // End of event
                        if (eventType != null && dataBuilder.length() > 0) {
                            processEvent(eventType, dataBuilder.toString());
                        }
                        eventType = null;
                        dataBuilder.setLength(0);
                    } else if (line.startsWith("event:")) {
                        eventType = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        dataBuilder.append(line.substring(5).trim());
                    } else if (line.startsWith(":")) {
                        // Comment (heartbeat), ignore
                        log.trace("SSE heartbeat received");
                    }
                }
            }
        } finally {
            connected.set(false);
            conn.disconnect();
        }
    }

    /**
     * Processes a received SSE event.
     */
    private void processEvent(String eventType, String data) {
        try {
            if ("connected".equals(eventType)) {
                log.info("SSE stream connected: {}", data);
                return;
            }

            if ("transaction_started".equals(eventType) || "transaction_completed".equals(eventType)) {
                // Parse the transaction data
                JsonNode json = objectMapper.readTree(data);

                DebugEvent event = new DebugEvent();
                event.setId(json.has("id") ? json.get("id").asText() : null);
                event.setTimestamp(json.has("startTime") ? json.get("startTime").asLong() : System.currentTimeMillis());
                event.setType("http");
                event.setTag(eventType);

                // Convert JSON to Map for data field
                Map<String, Object> dataMap = objectMapper.readValue(data,
                        new TypeReference<Map<String, Object>>() {});
                event.setData(dataMap);

                log.debug("Received {}: {} {}", eventType,
                        json.has("method") ? json.get("method").asText() : "?",
                        json.has("url") ? json.get("url").asText() : "?");

                // Notify consumer
                if (eventConsumer != null) {
                    eventConsumer.accept(event);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to process SSE event: {}", e.getMessage());
        }
    }
}
