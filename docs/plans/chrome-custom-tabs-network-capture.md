# Plan: Chrome Custom Tabs Network Capture via CDP

## Implementation Status

| Phase | Status | Notes |
|-------|--------|-------|
| Phase 1: Sidekick JVMTI Hooks | ✅ Complete | CustomTabsLaunchHook, CustomTabsInspector, CustomTabEvent |
| Phase 2: CDP Client Library | ✅ Complete | ChromeDevToolsClient, CdpTarget, CdpNetworkEvent, ChromeSocketDiscovery |
| Phase 3: Integration | ✅ Complete | CustomTabsNetworkMonitor orchestrates CDP + sidekick events |
| Phase 4: CLI Support | ✅ Complete | `dta-cli inspect customtabs` command |
| Phase 5: MCP Support | 🔄 Partial | SidekickClient methods added, MCP tools not yet exposed |
| Phase 6: Inspector Web | 🔜 Future | UI toggle not yet added |

---

## Goal

Capture HTTP network traffic from Chrome Custom Tabs by connecting to Chrome's DevTools Protocol (CDP) socket. This enables full request/response inspection for URLs opened in Custom Tabs, not just the intent data.

## Background

- **Current state**: Sidekick can capture OkHttp traffic via JVMTI hooks, but Custom Tabs run in Chrome's separate process
- **Limitation**: Sidekick (in-app) cannot directly access Chrome's DevTools socket due to Android sandboxing
- **Solution**: External tools (CLI/MCP) can connect via `adb forward` to Chrome's CDP socket

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Android Device                                                          │
│                                                                         │
│  ┌─────────────────────────┐      ┌──────────────────────────────────┐ │
│  │ App Process             │      │ Chrome Process                   │ │
│  │                         │      │                                  │ │
│  │  ┌───────────────────┐  │      │  ┌────────────────────────────┐ │ │
│  │  │ Sidekick          │  │      │  │ Custom Tab                 │ │ │
│  │  │                   │  │      │  │ - Loads URL                │ │ │
│  │  │ JVMTI Hook:       │  │      │  │ - Makes HTTP requests      │ │ │
│  │  │ CustomTabsIntent  │──┼──────┼─▶│                            │ │ │
│  │  │                   │  │      │  └────────────────────────────┘ │ │
│  │  └─────────┬─────────┘  │      │                                  │ │
│  │            │            │      │  @chrome_devtools_remote         │ │
│  │            │ SSE event  │      │            ▲                     │ │
│  └────────────┼────────────┘      └────────────┼─────────────────────┘ │
│               │                                │                        │
│               │                                │ Unix socket            │
└───────────────┼────────────────────────────────┼────────────────────────┘
                │                                │
                │ adb forward                    │ adb forward
                │ tcp:PORT                       │ tcp:9222
                ▼                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ Host Machine (CLI / MCP / Inspector)                                    │
│                                                                         │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │ ChromeDevToolsClient                                            │   │
│  │                                                                  │   │
│  │  1. Receives "customtab_opened" event from Sidekick             │   │
│  │  2. Connects to chrome_devtools_remote via adb forward          │   │
│  │  3. Polls /json/list to find tab by URL                         │   │
│  │  4. WebSocket connects to tab's debugger URL                    │   │
│  │  5. Sends Network.enable                                        │   │
│  │  6. Captures Network.requestWillBeSent, responseReceived, etc.  │   │
│  │  7. Merges events into existing network event stream            │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

## Implementation Phases

### Phase 1: Sidekick - Custom Tabs Detection (JVMTI Hooks)

#### 1.1 Create CustomTabsIntentHook

**File**: `dta-sidekick/src/main/java/io/yamsergey/dta/sidekick/hooks/customtabs/CustomTabsIntentHook.java`

Hook into `androidx.browser.customtabs.CustomTabsIntent.launchUrl()` to detect when Custom Tab is launched.

```java
public class CustomTabsIntentHook implements MethodHook {

    @Override
    public String targetClass() {
        return "androidx.browser.customtabs.CustomTabsIntent";
    }

    @Override
    public String targetMethod() {
        return "launchUrl";
    }

    @Override
    public String targetSignature() {
        // launchUrl(Context context, Uri url)
        return "(Landroid/content/Context;Landroid/net/Uri;)V";
    }

    @Override
    public void onEnter(Object thiz, Object[] args) {
        Uri url = (Uri) args[1];
        // Extract custom headers if set via Builder
        Map<String, String> headers = extractHeaders(thiz);

        CustomTabEvent event = new CustomTabEvent(
            url.toString(),
            headers,
            System.currentTimeMillis()
        );

        EventBus.publish(event);
    }
}
```

#### 1.2 Create CustomTabEvent

**File**: `dta-sidekick/src/main/java/io/yamsergey/dta/sidekick/events/CustomTabEvent.java`

```java
public record CustomTabEvent(
    String url,
    Map<String, String> headers,
    long timestamp
) implements SidekickEvent {

    @Override
    public String type() {
        return "customtab_opened";
    }
}
```

#### 1.3 Register Hook

**File**: `dta-sidekick/src/main/java/io/yamsergey/dta/sidekick/hooks/HookRegistry.java`

Add `CustomTabsIntentHook` to the list of registered hooks.

#### 1.4 Add SSE Event Type

**File**: `dta-sidekick/src/main/java/io/yamsergey/dta/sidekick/server/InspectorServer.java`

Ensure `customtab_opened` events are broadcast via SSE to connected clients.

---

### Phase 2: Tools Common - CDP Client Library

#### 2.1 Create ChromeDevToolsClient

**File**: `tools-android/src/main/java/io/yamsergey/dta/tools/cdp/ChromeDevToolsClient.java`

Core CDP client that handles connection and messaging.

```java
public class ChromeDevToolsClient implements AutoCloseable {

    private final String host;
    private final int port;
    private WebSocket webSocket;
    private final AtomicInteger messageId = new AtomicInteger(0);
    private final Map<Integer, CompletableFuture<JsonNode>> pendingRequests;
    private Consumer<CdpNetworkEvent> networkEventListener;

    public ChromeDevToolsClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * List all available targets (tabs).
     */
    public List<CdpTarget> listTargets() throws IOException {
        // GET http://{host}:{port}/json/list
    }

    /**
     * Connect to a specific target's WebSocket debugger URL.
     */
    public void attachToTarget(CdpTarget target) throws IOException {
        // WebSocket connect to target.webSocketDebuggerUrl
    }

    /**
     * Enable network event capture.
     */
    public void enableNetwork() {
        send("Network.enable", Map.of());
    }

    /**
     * Get response body for a request.
     */
    public CompletableFuture<String> getResponseBody(String requestId) {
        return send("Network.getResponseBody", Map.of("requestId", requestId));
    }

    /**
     * Set listener for network events.
     */
    public void setNetworkEventListener(Consumer<CdpNetworkEvent> listener) {
        this.networkEventListener = listener;
    }

    private CompletableFuture<JsonNode> send(String method, Map<String, Object> params) {
        int id = messageId.incrementAndGet();
        // Send JSON-RPC message over WebSocket
    }
}
```

#### 2.2 Create CDP Data Classes

**File**: `tools-android/src/main/java/io/yamsergey/dta/tools/cdp/CdpTarget.java`

```java
public record CdpTarget(
    String id,
    String type,
    String title,
    String url,
    String webSocketDebuggerUrl
) {}
```

**File**: `tools-android/src/main/java/io/yamsergey/dta/tools/cdp/CdpNetworkEvent.java`

```java
public sealed interface CdpNetworkEvent {

    record RequestWillBeSent(
        String requestId,
        String url,
        String method,
        Map<String, String> headers,
        String postData,
        long timestamp
    ) implements CdpNetworkEvent {}

    record ResponseReceived(
        String requestId,
        String url,
        int status,
        String statusText,
        Map<String, String> headers,
        String mimeType,
        long timestamp
    ) implements CdpNetworkEvent {}

    record LoadingFinished(
        String requestId,
        long encodedDataLength,
        long timestamp
    ) implements CdpNetworkEvent {}

    record LoadingFailed(
        String requestId,
        String errorText,
        boolean canceled,
        long timestamp
    ) implements CdpNetworkEvent {}
}
```

#### 2.3 ADB Chrome Socket Discovery

**File**: `tools-android/src/main/java/io/yamsergey/dta/tools/cdp/ChromeSocketDiscovery.java`

```java
public class ChromeSocketDiscovery {

    /**
     * Find Chrome DevTools sockets on device.
     */
    public static List<String> findChromeDevToolsSockets(String deviceSerial)
            throws IOException, InterruptedException {
        // adb shell cat /proc/net/unix | grep devtools_remote
        // Returns: chrome_devtools_remote, webview_devtools_remote_{pid}, etc.
    }

    /**
     * Setup port forward to Chrome DevTools socket.
     */
    public static int forwardToChrome(String deviceSerial, String socketName)
            throws IOException, InterruptedException {
        // adb forward tcp:{port} localabstract:{socketName}
        // Returns the local port
    }
}
```

---

### Phase 3: Integration - Custom Tabs Network Monitor

#### 3.1 Create CustomTabsNetworkMonitor

**File**: `tools-android/src/main/java/io/yamsergey/dta/tools/cdp/CustomTabsNetworkMonitor.java`

Orchestrates the connection between sidekick events and CDP capture.

```java
public class CustomTabsNetworkMonitor implements AutoCloseable {

    private final String deviceSerial;
    private final Consumer<NetworkEvent> networkEventCallback;
    private ChromeDevToolsClient cdpClient;
    private int forwardedPort;

    public CustomTabsNetworkMonitor(
            String deviceSerial,
            Consumer<NetworkEvent> networkEventCallback) {
        this.deviceSerial = deviceSerial;
        this.networkEventCallback = networkEventCallback;
    }

    /**
     * Called when sidekick reports a Custom Tab was opened.
     */
    public void onCustomTabOpened(String url, Map<String, String> headers, long timestamp) {
        // 1. Find/setup Chrome DevTools socket
        // 2. Poll /json/list until tab with matching URL appears
        // 3. Attach to tab
        // 4. Enable Network capture
        // 5. Convert CDP events to NetworkEvent and forward to callback
    }

    /**
     * Pre-connect to Chrome DevTools (optional optimization).
     */
    public void preConnect() {
        // Setup adb forward and create CDP client
        // Don't attach to any tab yet
    }

    private CdpTarget waitForTab(String url, Duration timeout) {
        // Poll /json/list until tab with URL appears
        // Use URL prefix matching since redirects may change exact URL
    }

    private NetworkEvent convertToNetworkEvent(CdpNetworkEvent cdpEvent) {
        // Convert CDP network events to existing NetworkEvent format
        // Mark source as "customtab" to distinguish from OkHttp
    }
}
```

#### 3.2 Extend NetworkEvent

**File**: `tools-android/src/main/java/io/yamsergey/dta/tools/android/inspect/network/NetworkEvent.java`

Add source field to distinguish Custom Tab traffic:

```java
public record NetworkEvent(
    // ... existing fields ...
    String source  // "okhttp", "customtab", "webview"
) {}
```

---

### Phase 4: CLI Integration

#### 4.1 Add Custom Tabs Support to Network Command

**File**: `dta-cli/src/main/java/io/yamsergey/dta/cli/inspect/NetworkCommand.java`

```java
@Command(name = "network", description = "Monitor network traffic")
public class NetworkCommand implements Runnable {

    @Option(names = "--include-customtabs",
            description = "Also capture Chrome Custom Tabs traffic via CDP")
    boolean includeCustomTabs = false;

    @Override
    public void run() {
        // ... existing SSE connection to sidekick ...

        if (includeCustomTabs) {
            CustomTabsNetworkMonitor monitor = new CustomTabsNetworkMonitor(
                deviceSerial,
                this::handleNetworkEvent
            );
            monitor.preConnect();

            // Listen for customtab_opened events
            sseClient.on("customtab_opened", event -> {
                monitor.onCustomTabOpened(
                    event.url(),
                    event.headers(),
                    event.timestamp()
                );
            });
        }
    }
}
```

---

### Phase 5: MCP Integration

#### 5.1 Add Custom Tabs Support to MCP

**File**: `dta-mcp/src/main/java/io/yamsergey/dta/mcp/McpServer.java`

Add tool or option to enable Custom Tabs network capture:

```java
// Option 1: Separate tool
@Tool(name = "enable_customtabs_capture")
public String enableCustomTabsCapture() {
    customTabsMonitor = new CustomTabsNetworkMonitor(...);
    customTabsMonitor.preConnect();
    return "Custom Tabs network capture enabled";
}

// Option 2: Parameter on existing network tool
@Tool(name = "get_network_events")
public List<NetworkEvent> getNetworkEvents(
    @Param boolean includeCustomTabs
) {
    // ...
}
```

---

### Phase 6: Inspector Web Integration

#### 6.1 Add Custom Tabs Toggle

**File**: `dta-inspector-web/src/main/resources/static/index.html`

Add UI toggle to enable Custom Tabs capture:

```html
<label>
  <input type="checkbox" id="include-customtabs" />
  Include Chrome Custom Tabs traffic
</label>
```

#### 6.2 Backend Support

**File**: `dta-inspector-web/src/main/java/io/yamsergey/dta/inspector/web/InspectorController.java`

```java
@PostMapping("/api/customtabs/enable")
public ResponseEntity<String> enableCustomTabs() {
    // Start CustomTabsNetworkMonitor
}

@PostMapping("/api/customtabs/disable")
public ResponseEntity<String> disableCustomTabs() {
    // Stop CustomTabsNetworkMonitor
}
```

---

## CDP Network Events Reference

### Events to Handle

| CDP Event | Description | Data Available |
|-----------|-------------|----------------|
| `Network.requestWillBeSent` | Request is about to be sent | URL, method, headers, postData, timestamp |
| `Network.responseReceived` | Response headers received | status, headers, mimeType |
| `Network.loadingFinished` | Request completed | encodedDataLength |
| `Network.loadingFailed` | Request failed | errorText, canceled |
| `Network.dataReceived` | Chunk of data received | dataLength |

### Getting Response Body

Response body is not included in events. Must be fetched explicitly:

```json
// Request
{"id": 1, "method": "Network.getResponseBody", "params": {"requestId": "123"}}

// Response
{"id": 1, "result": {"body": "...", "base64Encoded": false}}
```

---

## Testing Plan

### Unit Tests

1. `ChromeDevToolsClientTest` - Test CDP message parsing
2. `CdpNetworkEventTest` - Test event deserialization
3. `ChromeSocketDiscoveryTest` - Test socket name parsing

### Integration Tests

1. Connect to real Chrome on device
2. Open Custom Tab with known URL
3. Verify network events captured
4. Verify response body retrieval

### Manual Testing

1. Start app with sidekick
2. Start CLI with `--include-customtabs`
3. Trigger Custom Tab in app
4. Verify network events appear
5. Check request/response details

---

## Dependencies

### New Dependencies

**tools-android/build.gradle**:
```groovy
// WebSocket client for CDP
implementation 'org.java-websocket:Java-WebSocket:1.5.6'
```

### Existing Dependencies (already available)

- Jackson for JSON parsing
- OkHttp for HTTP requests (GET /json/list)

---

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Chrome DevTools not enabled | Feature won't work | Detect and show clear error message |
| Tab URL mismatch (redirects) | Can't find tab | Use URL prefix matching, retry with final URL |
| Timing - miss initial request | Incomplete capture | Pre-connect to CDP, warn user about limitation |
| Multiple Chrome instances | Attach to wrong one | Scan all devtools sockets, match by tab URL |
| WebSocket connection drops | Lost events | Implement reconnection logic |

---

## Future Enhancements

1. **WebView support**: Same CDP approach for in-app WebViews
2. **Request interception**: Use `Fetch.enable` to modify requests
3. **Performance metrics**: Capture `Performance` domain events
4. **Console logs**: Capture `Console` and `Log` domain events

---

## Files Summary

| Module | File | Change |
|--------|------|--------|
| dta-sidekick | hooks/customtabs/CustomTabsIntentHook.java | NEW - JVMTI hook |
| dta-sidekick | events/CustomTabEvent.java | NEW - Event type |
| dta-sidekick | hooks/HookRegistry.java | Register new hook |
| tools-android | cdp/ChromeDevToolsClient.java | NEW - CDP client |
| tools-android | cdp/CdpTarget.java | NEW - Data class |
| tools-android | cdp/CdpNetworkEvent.java | NEW - Event types |
| tools-android | cdp/ChromeSocketDiscovery.java | NEW - ADB utils |
| tools-android | cdp/CustomTabsNetworkMonitor.java | NEW - Orchestrator |
| tools-android | build.gradle | Add WebSocket dependency |
| dta-cli | inspect/NetworkCommand.java | Add --include-customtabs |
| dta-mcp | McpServer.java | Add CDP support |
| dta-inspector-web | InspectorController.java | Add endpoints |
| dta-inspector-web | index.html | Add UI toggle |
