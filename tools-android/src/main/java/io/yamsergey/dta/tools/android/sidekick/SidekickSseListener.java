package io.yamsergey.dta.tools.android.sidekick;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE client that subscribes to sidekick's event stream.
 *
 * <p>Listens to {@code /events/stream} and dispatches events to the registered
 * listener. Used by the daemon to react to sidekick push events like Custom Tab
 * launches instead of polling.</p>
 */
public class SidekickSseListener implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SidekickSseListener.class);
    private static final long RECONNECT_DELAY_MS = 2000;

    public interface EventListener {
        void onCustomTabWillLaunch(String eventId, String url, long timestamp);
        void onConnected();
        void onDisconnected();
    }

    private final int port;
    private final EventListener listener;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread listenerThread;

    public SidekickSseListener(int port, EventListener listener) {
        this.port = port;
        this.listener = listener;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            listenerThread = new Thread(this::listenLoop, "sidekick-sse-" + port);
            listenerThread.setDaemon(true);
            listenerThread.start();
            log.info("SSE listener started on port {}", port);
        }
    }

    public void stop() {
        running.set(false);
        if (listenerThread != null) {
            listenerThread.interrupt();
            listenerThread = null;
        }
        log.info("SSE listener stopped");
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running.get();
    }

    private void listenLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                connectAndListen();
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                if (running.get()) {
                    log.debug("SSE connection lost, reconnecting in {}ms: {}", RECONNECT_DELAY_MS, e.getMessage());
                    try {
                        listener.onDisconnected();
                        Thread.sleep(RECONNECT_DELAY_MS);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        }
        running.set(false);
    }

    private void connectAndListen() throws Exception {
        URL url = new URL("http://localhost:" + port + "/events/stream");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(0); // Infinite read timeout for SSE
        conn.setRequestProperty("Accept", "text/event-stream");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {

            String currentEvent = null;
            StringBuilder dataBuilder = new StringBuilder();

            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    currentEvent = line.substring(7).trim();
                } else if (line.startsWith("data: ")) {
                    if (dataBuilder.length() > 0) dataBuilder.append('\n');
                    dataBuilder.append(line.substring(6));
                } else if (line.startsWith(":")) {
                    // Comment/heartbeat — ignore
                } else if (line.isEmpty()) {
                    // End of event
                    if (currentEvent != null && dataBuilder.length() > 0) {
                        handleEvent(currentEvent, dataBuilder.toString());
                    }
                    currentEvent = null;
                    dataBuilder.setLength(0);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private void handleEvent(String eventType, String data) {
        try {
            switch (eventType) {
                case "connected" -> listener.onConnected();
                case "customtab_will_launch" -> {
                    JsonNode node = mapper.readTree(data);
                    String eventId = node.path("id").asText();
                    String eventUrl = node.path("url").asText();
                    long timestamp = node.path("timestamp").asLong();
                    listener.onCustomTabWillLaunch(eventId, eventUrl, timestamp);
                }
                default -> log.debug("Ignoring SSE event: {}", eventType);
            }
        } catch (Exception e) {
            log.warn("Error handling SSE event '{}': {}", eventType, e.getMessage());
        }
    }
}
