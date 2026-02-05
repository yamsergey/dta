package io.yamsergey.dta.tools.android.cdp;

import io.yamsergey.dta.tools.android.inspect.compose.SidekickClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Manages CDP watchers for Custom Tabs network monitoring.
 *
 * <p>This singleton ensures only one watcher runs per package/device combination,
 * preventing duplicate monitoring when multiple components (inspector-web, MCP)
 * might try to start watching the same app.</p>
 */
public class CdpWatcherManager {

    private static final CdpWatcherManager INSTANCE = new CdpWatcherManager();

    private final Map<String, WatcherContext> activeWatchers = new ConcurrentHashMap<>();

    private CdpWatcherManager() {}

    public static CdpWatcherManager getInstance() {
        return INSTANCE;
    }

    /**
     * Information about an active watcher.
     */
    public record WatcherInfo(
        String packageName,
        String deviceSerial,
        String currentTabUrl,
        boolean isConnected,
        long startTime
    ) {}

    /**
     * Starts a CDP watcher for the given package if not already running.
     *
     * @param packageName the Android package name
     * @param deviceSerial the device serial (or null for default)
     * @param cdpPort the local port for Chrome DevTools
     * @param sidekickClient the sidekick client for posting transactions
     * @param eventCallback optional callback for network events
     * @return true if watcher was started, false if already running
     */
    public synchronized boolean startWatcher(
            String packageName,
            String deviceSerial,
            int cdpPort,
            SidekickClient sidekickClient,
            Consumer<CustomTabsNetworkMonitor.CustomTabNetworkEvent> eventCallback) {

        String key = makeKey(packageName, deviceSerial);

        if (activeWatchers.containsKey(key)) {
            // Already watching
            return false;
        }

        WatcherContext context = new WatcherContext(
            packageName, deviceSerial, cdpPort, sidekickClient, eventCallback
        );
        activeWatchers.put(key, context);
        context.start();

        return true;
    }

    /**
     * Stops the CDP watcher for the given package.
     *
     * @param packageName the Android package name
     * @param deviceSerial the device serial (or null for default)
     * @return true if watcher was stopped, false if not running
     */
    public synchronized boolean stopWatcher(String packageName, String deviceSerial) {
        String key = makeKey(packageName, deviceSerial);
        WatcherContext context = activeWatchers.remove(key);

        if (context != null) {
            context.stop();
            return true;
        }
        return false;
    }

    /**
     * Checks if a watcher is active for the given package.
     */
    public boolean isWatching(String packageName, String deviceSerial) {
        return activeWatchers.containsKey(makeKey(packageName, deviceSerial));
    }

    /**
     * Gets info about an active watcher.
     */
    public WatcherInfo getWatcherInfo(String packageName, String deviceSerial) {
        WatcherContext context = activeWatchers.get(makeKey(packageName, deviceSerial));
        if (context == null) {
            return null;
        }
        return context.getInfo();
    }

    /**
     * Gets info about all active watchers.
     */
    public List<WatcherInfo> getAllWatchers() {
        return activeWatchers.values().stream()
            .map(WatcherContext::getInfo)
            .toList();
    }

    /**
     * Stops all active watchers.
     */
    public synchronized void stopAll() {
        activeWatchers.values().forEach(WatcherContext::stop);
        activeWatchers.clear();
    }

    private String makeKey(String packageName, String deviceSerial) {
        return (deviceSerial != null ? deviceSerial : "default") + ":" + packageName;
    }

    /**
     * Internal context for a running watcher.
     */
    private static class WatcherContext {
        private final String packageName;
        private final String deviceSerial;
        private final int cdpPort;
        private final SidekickClient sidekickClient;
        private final Consumer<CustomTabsNetworkMonitor.CustomTabNetworkEvent> eventCallback;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final long startTime;

        private Thread watcherThread;
        private CustomTabsNetworkMonitor monitor;
        private ChromeDevToolsClient currentClient;
        private volatile String currentTabUrl;

        WatcherContext(
                String packageName,
                String deviceSerial,
                int cdpPort,
                SidekickClient sidekickClient,
                Consumer<CustomTabsNetworkMonitor.CustomTabNetworkEvent> eventCallback) {
            this.packageName = packageName;
            this.deviceSerial = deviceSerial;
            this.cdpPort = cdpPort;
            this.sidekickClient = sidekickClient;
            this.eventCallback = eventCallback;
            this.startTime = System.currentTimeMillis();
        }

        void start() {
            if (running.compareAndSet(false, true)) {
                monitor = new CustomTabsNetworkMonitor(deviceSerial, eventCallback);
                monitor.setSidekickClient(sidekickClient);

                watcherThread = new Thread(this::watchLoop, "cdp-watcher-" + packageName);
                watcherThread.setDaemon(true);
                watcherThread.start();
            }
        }

        void stop() {
            running.set(false);
            if (watcherThread != null) {
                watcherThread.interrupt();
            }
            if (currentClient != null) {
                try {
                    currentClient.close();
                } catch (Exception ignored) {}
            }
        }

        WatcherInfo getInfo() {
            return new WatcherInfo(
                packageName,
                deviceSerial,
                currentTabUrl,
                currentClient != null,
                startTime
            );
        }

        private void watchLoop() {
            String currentTabId = null;

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    CdpTarget tab = findCustomTab();

                    if (tab != null && !tab.id().equals(currentTabId)) {
                        // New tab detected
                        closeCurrentClient();

                        ChromeDevToolsClient client = new ChromeDevToolsClient("localhost", cdpPort);
                        client.attachToTarget(tab);
                        monitor.setCdpClient(client);

                        currentTabUrl = tab.url();
                        client.setNetworkEventListener(cdpEvent -> {
                            monitor.onCdpEvent(cdpEvent, currentTabUrl);
                        });

                        client.enableNetwork().join();

                        currentTabId = tab.id();
                        currentClient = client;

                    } else if (tab == null && currentTabId != null) {
                        // Tab closed
                        closeCurrentClient();
                        currentTabId = null;
                        currentTabUrl = null;
                    }

                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    // Connection errors when Chrome isn't running
                    if (currentTabId != null) {
                        closeCurrentClient();
                        currentTabId = null;
                        currentTabUrl = null;
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        }

        private void closeCurrentClient() {
            if (currentClient != null) {
                try {
                    currentClient.close();
                } catch (Exception ignored) {}
                currentClient = null;
            }
            monitor.setCdpClient(null);
        }

        private CdpTarget findCustomTab() {
            try {
                ChromeDevToolsClient client = new ChromeDevToolsClient("localhost", cdpPort);
                List<CdpTarget> targets = client.listTargets();
                return targets.stream()
                    .filter(CdpTarget::isPage)
                    .findFirst()
                    .orElse(null);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
