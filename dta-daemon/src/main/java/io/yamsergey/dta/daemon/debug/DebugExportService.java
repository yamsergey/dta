package io.yamsergey.dta.daemon.debug;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.yamsergey.dta.daemon.sidekick.SidekickClient;
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager;
import io.yamsergey.dta.daemon.sidekick.SidekickConnectionManager.ConnectionInfo;
import io.yamsergey.dta.tools.sugar.Result;
import io.yamsergey.dta.tools.sugar.Success;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the "Export Debug Logs" zip the plugin's Daemon panel offers.
 *
 * <p>Three orthogonal sources end up in the bundle:
 * <ul>
 *   <li>{@code sidekick.log} — file-logger output from inside the host
 *       process. Pulled via {@code adb shell run-as <pkg> cat ...}. This
 *       is the only source for SidekickLog-tagged events that have rolled
 *       out of logcat already.</li>
 *   <li>{@code logcat-filtered.txt} — {@code adb logcat -d -t 5000}
 *       filtered to dta-related tags. Captures the boot-class layer
 *       (BootstrapShim, JvmtiAgent, DexTransformer, native SidekickAgent)
 *       which writes through {@code android.util.Log} directly, NOT
 *       SidekickLog — so it never reaches the file. Without this slice,
 *       the most common real-device failures (NCDFE on first hook fire,
 *       AbstractMethodError from d8 desugaring) would be invisible.</li>
 *   <li>{@code state.json} — single-shot snapshot from the sidekick's
 *       /debug/diagnostics + /health + /runtime/lifecycle + navigation,
 *       plus daemon version. Tells us which hooks are registered, whether
 *       BootstrapShim attached, what API level/device we're on.</li>
 * </ul></p>
 *
 * <p>If {@code redact=true} (the default), text content runs through
 * {@link DebugLogRedactor} before going into the zip — Authorization /
 * Cookie / Set-Cookie / X-Api-Key header values, JWTs, emails, and the
 * host package name are masked. Users opening a GitHub issue with the
 * bundle attached should be safe to share without skimming first.</p>
 *
 * <p>Failures of any one source are non-fatal: a missing sidekick.log
 * (file logging disabled) or a failed run-as (non-debuggable on this
 * device) just leaves a placeholder in the zip with the error reason.
 * The user gets the bundle anyway and can see what's missing in
 * {@code manifest.txt}.</p>
 */
public class DebugExportService {

    private static final Logger log = LoggerFactory.getLogger(DebugExportService.class);

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /**
     * Tags grep'd from {@code adb logcat}. Each maps to a layer:
     * <ul>
     *   <li>BootstrapShim / DtaBootShim — install-path log line. Missing
     *       = boot-class hooks won't fire.</li>
     *   <li>JvmtiAgent / SidekickAgent / ClassTransformer / DexTransformer —
     *       transformation pipeline. Missing transformations = Layer 1
     *       filter dropped the load, OR the hook isn't registered.</li>
     *   <li>HookDispatcher / HookRegistry / HookManager — dispatch path.
     *       NCDFE / AbstractMethodError land here.</li>
     *   <li>ChromeIntentHook / CustomTabsHook — concrete hook fires.</li>
     *   <li>ADT-Sidekick / InspectorServer — server lifecycle.</li>
     *   <li>AndroidRuntime — fatal exceptions. Catches the rare case
     *       where ART rejects the transformed class entirely.</li>
     * </ul>
     */
    private static final List<String> LOGCAT_TAGS = List.of(
            "DtaBootShim",
            "BootstrapShim",
            "JvmtiAgent",
            "SidekickAgent",
            "ClassTransformer",
            "DexTransformer",
            "HookDispatcher",
            "HookRegistry",
            "HookManager",
            "ChromeIntentHook",
            "CustomTabsHook",
            "ADT-Sidekick",
            "InspectorServer",
            "ActivityIntentHook",
            "AndroidRuntime"
    );

    private static final int LOGCAT_TAIL_LINES = 5000;

    private final SidekickConnectionManager connectionManager = SidekickConnectionManager.getInstance();
    private final String daemonVersion;

    public DebugExportService(String daemonVersion) {
        this.daemonVersion = daemonVersion;
    }

    /**
     * Builds the bundle. Synchronous — caller (the route handler) is
     * already on a worker thread under Javalin.
     */
    public byte[] export(String packageName, String device, boolean redact) throws Exception {
        if (packageName == null || packageName.isEmpty()) {
            throw new IllegalArgumentException("package query parameter is required");
        }

        Instant capturedAt = Instant.now();
        Map<String, BundleEntry> entries = new LinkedHashMap<>();

        // Resolve connection up front; we use it for every sidekick fetch
        // and also need to know the active device serial when no explicit
        // device was provided.
        ConnectionInfo conn;
        try {
            conn = connectionManager.getConnection(packageName, device);
        } catch (Exception e) {
            log.warn("No sidekick connection for {} on {}: {}", packageName, device, e.getMessage());
            conn = null;
        }
        String resolvedDevice = device;
        if (conn != null && (resolvedDevice == null || resolvedDevice.isEmpty())) {
            resolvedDevice = conn.device();
        }

        // 1. State snapshot — we want this even when other sources fail.
        try {
            byte[] stateJson = collectState(conn, packageName, resolvedDevice, capturedAt);
            entries.put("state.json", new BundleEntry(stateJson, true, null));
        } catch (Exception e) {
            entries.put("state.json", new BundleEntry(
                    placeholder("state.json collection failed", e), false, e.getMessage()));
        }

        // 2. Sidekick file log via run-as. Uses --as-bytes-stdout-fully so
        //    this works even on devices where SELinux happens to grant the
        //    raw read but not list-dir.
        try {
            byte[] sidekickLog = pullSidekickLog(packageName, resolvedDevice);
            entries.put("sidekick.log", new BundleEntry(sidekickLog, true, null));
        } catch (Exception e) {
            entries.put("sidekick.log", new BundleEntry(
                    placeholder("sidekick.log unavailable — file logging may be off, or app is non-debuggable", e),
                    false, e.getMessage()));
        }

        // 3. Logcat tail filtered to our tag set.
        try {
            byte[] logcat = collectLogcat(resolvedDevice);
            entries.put("logcat-filtered.txt", new BundleEntry(logcat, true, null));
        } catch (Exception e) {
            entries.put("logcat-filtered.txt", new BundleEntry(
                    placeholder("logcat collection failed", e), false, e.getMessage()));
        }

        // 4. CCT launch traces — daemon-side ring buffer of recent Custom
        //    Tab / chrome_will_launch handling, with per-step timing and
        //    a /json/list snapshot when stuck. Doesn't depend on a sidekick
        //    connection; in-memory on the daemon.
        try {
            byte[] cctTraces = collectCctTraces();
            entries.put("cct-traces.json", new BundleEntry(cctTraces, true, null));
        } catch (Exception e) {
            entries.put("cct-traces.json", new BundleEntry(
                    placeholder("cct-traces collection failed", e), false, e.getMessage()));
        }

        // 4. Manifest documenting what's in the bundle. Built last so it
        //    can list per-source success/failure for each entry above.
        byte[] manifest = buildManifest(packageName, resolvedDevice, capturedAt, redact, entries);
        entries.put("manifest.txt", new BundleEntry(manifest, true, null));

        // Redact text content if requested — JSON, log files, manifest are
        // all UTF-8 text. We mutate the entries map in-place because
        // redaction is a pure transform on bytes.
        if (redact) {
            DebugLogRedactor redactor = new DebugLogRedactor(packageName);
            for (Map.Entry<String, BundleEntry> e : entries.entrySet()) {
                String name = e.getKey();
                BundleEntry entry = e.getValue();
                if (!entry.included) continue;
                if (!isTextEntry(name)) continue;
                String text = new String(entry.content, StandardCharsets.UTF_8);
                String redacted = redactor.redact(text);
                e.setValue(new BundleEntry(redacted.getBytes(StandardCharsets.UTF_8), true, null));
            }
        }

        return zip(entries);
    }

    // ========================================================================
    // Source: state snapshot
    // ========================================================================

    private byte[] collectState(ConnectionInfo conn, String packageName, String device, Instant capturedAt)
            throws Exception {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("capturedAt", DateTimeFormatter.ISO_INSTANT.format(capturedAt));
        state.put("packageName", packageName);
        state.put("device", device != null ? device : "(unset)");
        state.put("daemonVersion", daemonVersion);

        if (conn == null) {
            state.put("error", "no sidekick connection");
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(state);
        }

        SidekickClient client = conn.client();
        // Wrap each fetch — the diagnostics endpoint is the only must-have;
        // health/lifecycle/navigation are nice-to-have and shouldn't fail
        // the whole bundle if the user's app doesn't run them.
        state.put("diagnostics", parseOrError(client.debugDiagnostics()));
        state.put("health", parseOrError(client.checkHealth()));
        state.put("lifecycle", parseOrError(client.lifecycle()));
        state.put("navigationBackstack", parseOrError(client.navigationBackstack()));
        state.put("navigationGraph", parseOrError(client.navigationGraph()));

        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(state);
    }

    private Object parseOrError(Result<String> result) {
        if (result instanceof Success<String> success) {
            try {
                return MAPPER.readTree(success.value());
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("error", "parse failed: " + e.getMessage());
                err.put("raw", success.value());
                return err;
            }
        }
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "fetch failed: " + result.toString());
        return err;
    }

    // ========================================================================
    // Source: sidekick.log via run-as
    // ========================================================================

    private byte[] pullSidekickLog(String packageName, String device) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(adbExecutable());
        if (device != null && !device.isEmpty()) {
            cmd.add("-s");
            cmd.add(device);
        }
        cmd.add("shell");
        cmd.add("run-as");
        cmd.add(packageName);
        cmd.add("cat");
        // Mirrors SidekickInitializer's path: cacheDir/sidekick.log.
        // run-as runs in the app's working directory, so a relative path
        // is the cleanest expression.
        cmd.add("cache/sidekick.log");

        return runAndCollect(cmd, 30_000);
    }

    // ========================================================================
    // Source: CCT launch traces
    // ========================================================================

    private byte[] collectCctTraces() throws Exception {
        var trace = io.yamsergey.dta.daemon.cdp.CctLaunchTrace.getInstance();
        var entries = trace.snapshot(0);
        Map<String, Object> root = new LinkedHashMap<>();
        List<Map<String, Object>> arr = new ArrayList<>(entries.size());
        for (var e : entries) arr.add(e.toMap());
        root.put("traces", arr);
        // Pretty-printed for grep / human inspection in the bundle.
        return new tools.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(root);
    }

    // ========================================================================
    // Source: logcat tail
    // ========================================================================

    private byte[] collectLogcat(String device) throws Exception {
        // adb logcat -d -t N: dump everything currently in the buffer
        // up to N most recent lines (across all tags). We then grep
        // client-side because the device-side filter would need per-tag
        // priority specs and silently drops anything not in the list.
        List<String> cmd = new ArrayList<>();
        cmd.add(adbExecutable());
        if (device != null && !device.isEmpty()) {
            cmd.add("-s");
            cmd.add(device);
        }
        cmd.add("logcat");
        cmd.add("-d");
        cmd.add("-t");
        cmd.add(String.valueOf(LOGCAT_TAIL_LINES));

        byte[] raw = runAndCollect(cmd, 30_000);
        String output = new String(raw, StandardCharsets.UTF_8);

        // Filter lines by tag. logcat default format puts the tag at
        // column ~33 (after the timestamp+pid+tid+priority prefix).
        // Robust enough to grep for "TAG:" appearing after column ~30.
        StringBuilder filtered = new StringBuilder();
        for (String line : output.split("\n")) {
            for (String tag : LOGCAT_TAGS) {
                if (line.contains(" " + tag + ":") || line.contains(" " + tag + " ")) {
                    filtered.append(line).append('\n');
                    break;
                }
            }
        }
        if (filtered.length() == 0) {
            filtered.append("(no matching log entries — check that the app has been launched recently and that DTA tags are emitted)\n");
        }
        return filtered.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ========================================================================
    // Manifest
    // ========================================================================

    private byte[] buildManifest(String packageName, String device, Instant capturedAt,
                                 boolean redacted, Map<String, BundleEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("DTA Debug Bundle\n");
        sb.append("================\n\n");
        sb.append("captured: ").append(DateTimeFormatter.ISO_INSTANT.format(capturedAt)).append('\n');
        sb.append("local time: ").append(capturedAt.atZone(ZoneOffset.systemDefault()).format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))).append('\n');
        sb.append("package: ").append(redacted ? "<redacted>" : packageName).append('\n');
        sb.append("device: ").append(device != null ? device : "(unset)").append('\n');
        sb.append("daemon version: ").append(daemonVersion).append('\n');
        sb.append("redacted: ").append(redacted).append('\n');
        if (redacted) {
            sb.append("\nRedaction rules applied:\n");
            sb.append("- Host package name → app_<8hex hash>\n");
            sb.append("- Authorization / Cookie / Set-Cookie / X-Api-Key header values → [REDACTED]\n");
            sb.append("- JWT tokens (eyJ... pattern) → [JWT_REDACTED]\n");
            sb.append("- Email addresses → [EMAIL_REDACTED]\n");
        }
        sb.append("\nFiles:\n");
        for (Map.Entry<String, BundleEntry> e : entries.entrySet()) {
            BundleEntry entry = e.getValue();
            sb.append("- ").append(e.getKey()).append(": ");
            if (entry.included) {
                sb.append(entry.content.length).append(" bytes");
            } else {
                sb.append("MISSING — ").append(entry.errorReason);
            }
            sb.append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Returns the ADB executable to invoke. Prefers the path the daemon
     * has been told about (via {@code SidekickConnectionManager.setAdbPath}
     * — typically by the AS plugin pointing at the IDE's bundled SDK),
     * falls back to bare "adb" if no override is set. Without this, the
     * bundle collection silently fails inside Android Studio because
     * adb isn't on the IDE process's PATH.
     */
    private static String adbExecutable() {
        String configured = SidekickConnectionManager.getAdbPath();
        return (configured != null && !configured.isEmpty()) ? configured : "adb";
    }

    private byte[] runAndCollect(List<String> cmd, long timeoutMillis) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long deadline = System.currentTimeMillis() + timeoutMillis;
        try (var in = process.getInputStream()) {
            while (System.currentTimeMillis() < deadline) {
                if (in.available() > 0) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    baos.write(buf, 0, n);
                } else if (!process.isAlive()) {
                    int n = in.read(buf);
                    if (n > 0) baos.write(buf, 0, n);
                    if (n < 0) break;
                } else {
                    Thread.sleep(20);
                }
            }
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            throw new RuntimeException("command timed out after " + timeoutMillis + "ms: " + String.join(" ", cmd));
        }
        if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
            String tail = baos.toString(StandardCharsets.UTF_8);
            throw new RuntimeException("command failed (exit=" + process.exitValue() + "): " +
                    (tail.length() > 500 ? tail.substring(0, 500) + "…" : tail));
        }
        return baos.toByteArray();
    }

    private byte[] zip(Map<String, BundleEntry> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, BundleEntry> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().content);
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private boolean isTextEntry(String name) {
        return name.endsWith(".log") || name.endsWith(".txt") || name.endsWith(".json");
    }

    private byte[] placeholder(String summary, Throwable t) {
        return ("[unavailable] " + summary + "\nreason: " + t.getMessage() + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Bundle entry with content + inclusion flag. errorReason is set when included=false. */
    private static final class BundleEntry {
        final byte[] content;
        final boolean included;
        final String errorReason;
        BundleEntry(byte[] content, boolean included, String errorReason) {
            this.content = content;
            this.included = included;
            this.errorReason = errorReason;
        }
    }
}
