package io.yamsergey.dta.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Writes / removes the {@code dta} entry in MCP client config files for
 * known agents. Used by both {@code dta-cli mcp install/uninstall} and the
 * plugin's MCP setup tab — single shared implementation, single config schema.
 *
 * <p>Every install:</p>
 * <ol>
 *   <li>Reads the existing {@code mcp.json} (if any)</li>
 *   <li>Backs it up to {@code mcp.json.bak} (overwriting any prior backup)</li>
 *   <li>JSON-merges our {@code dta} entry into {@code mcpServers}, preserving
 *       every other server the user has configured</li>
 *   <li>Writes atomically via temp file + {@link StandardCopyOption#ATOMIC_MOVE}</li>
 * </ol>
 *
 * <p>Uninstall removes only the {@code dta} entry, leaving the rest untouched.
 * If removing it leaves {@code mcpServers} empty, the empty object stays —
 * we don't second-guess the user's other tooling.</p>
 */
public class McpInstaller {

    private static final Logger log = LoggerFactory.getLogger(McpInstaller.class);
    private static final String DTA_ENTRY_KEY = "dta";
    private static final ObjectMapper mapper = new ObjectMapper();

    public enum Target {
        /** Android Studio Gemini — {@code <as-config-dir>/options/mcp.json}. */
        AS_GEMINI,
        /** Claude Code — {@code ~/.claude.json}. */
        CLAUDE_CODE,
        /** Copy the JSON snippet to the system clipboard. */
        CLIPBOARD,
        /** Print the JSON snippet to stdout. */
        PRINT
    }

    /**
     * Result of an install or uninstall operation.
     *
     * @param success    whether the operation succeeded
     * @param path       the file path written / read (null for clipboard/print/failures)
     * @param backupPath the {@code .bak} file created (null if no prior config existed)
     * @param message    human-readable status; on failure, the error message
     */
    public record Result(boolean success, Path path, Path backupPath, String message) {
        public static Result ok(Path path, Path backup, String message) {
            return new Result(true, path, backup, message);
        }
        public static Result fail(String message) {
            return new Result(false, null, null, message);
        }
    }

    /**
     * Generates the JSON snippet that points an MCP client at the local
     * dta-mcp HTTP server. Same shape used by {@link Target#AS_GEMINI},
     * {@link Target#CLAUDE_CODE}, and the clipboard/print fallbacks.
     *
     * @param port the dta-mcp HTTP server port
     * @return a pretty-printed JSON snippet, ready to paste
     */
    public static String snippet(int port) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode servers = root.putObject("mcpServers");
        ObjectNode dta = servers.putObject(DTA_ENTRY_KEY);
        dta.put("httpUrl", "http://localhost:" + port + "/mcp");
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return "{\n  \"mcpServers\": {\n    \"dta\": { \"httpUrl\": \"http://localhost:" + port + "/mcp\" }\n  }\n}";
        }
    }

    /**
     * Writes the {@code dta} entry into the target agent's config.
     *
     * @param target which agent to write to
     * @param port   the dta-mcp HTTP server port
     * @param hint   optional override for file location (e.g. when the plugin
     *               already knows {@code PathManager.getConfigPath()}); null
     *               to auto-discover
     */
    public static Result install(Target target, int port, Path hint) {
        return switch (target) {
            case AS_GEMINI -> writeMcpJson(resolveAsGeminiPath(hint), port);
            case CLAUDE_CODE -> writeMcpJson(resolveClaudeCodePath(hint), port);
            case CLIPBOARD -> copyToClipboard(snippet(port));
            case PRINT -> {
                System.out.println(snippet(port));
                yield Result.ok(null, null, "Snippet printed to stdout.");
            }
        };
    }

    /**
     * Removes the {@code dta} entry from the target agent's config.
     */
    public static Result uninstall(Target target, Path hint) {
        return switch (target) {
            case AS_GEMINI -> removeFromMcpJson(resolveAsGeminiPath(hint));
            case CLAUDE_CODE -> removeFromMcpJson(resolveClaudeCodePath(hint));
            case CLIPBOARD, PRINT -> Result.fail(
                target + " is not an installable target — nothing to uninstall.");
        };
    }

    // ========================================================================
    // Per-target path resolvers
    // ========================================================================

    /**
     * Resolves the AS Gemini {@code mcp.json} path. If {@code hint} is non-null
     * (e.g. {@code PathManager.getConfigPath() + "/options/mcp.json"} from
     * inside the plugin), uses it directly. Otherwise scans common AS config
     * locations and picks the most recently modified one.
     */
    public static Path resolveAsGeminiPath(Path hint) {
        if (hint != null) return hint;
        List<Path> candidates = findAndroidStudioConfigDirs();
        if (candidates.isEmpty()) return null;
        return candidates.get(0).resolve("options").resolve("mcp.json");
    }

    /**
     * Returns the user-level {@code ~/.claude.json} path. Project-level
     * overrides are out of scope for v1; users can pass {@code hint} to target
     * a specific project.
     */
    public static Path resolveClaudeCodePath(Path hint) {
        if (hint != null) return hint;
        return Path.of(System.getProperty("user.home"), ".claude.json");
    }

    /**
     * Scans common Android Studio config locations across platforms and
     * returns matching directories sorted by most-recently-modified first.
     * Returns an empty list if no AS install is detected.
     */
    public static List<Path> findAndroidStudioConfigDirs() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        List<Path> roots = new ArrayList<>();
        if (os.contains("mac")) {
            roots.add(Path.of(home, "Library", "Application Support", "Google"));
        } else if (os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            if (appdata != null) roots.add(Path.of(appdata, "Google"));
        } else {
            roots.add(Path.of(home, ".config", "Google"));
        }

        List<Path> matches = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> entries = Files.list(root)) {
                entries
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("AndroidStudio"))
                    .forEach(matches::add);
            } catch (IOException e) {
                log.debug("Failed to list AS config root {}: {}", root, e.getMessage());
            }
        }
        matches.sort(Comparator.comparing(McpInstaller::lastModified).reversed());
        return matches;
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    // ========================================================================
    // mcp.json read / merge / write
    // ========================================================================

    private static Result writeMcpJson(Path path, int port) {
        if (path == null) {
            return Result.fail("Could not determine target config path. Pass --hint with the absolute path.");
        }
        try {
            ObjectNode root = readOrCreate(path);
            ObjectNode servers = root.has("mcpServers") && root.get("mcpServers").isObject()
                ? (ObjectNode) root.get("mcpServers")
                : root.putObject("mcpServers");

            ObjectNode dta = mapper.createObjectNode();
            dta.put("httpUrl", "http://localhost:" + port + "/mcp");
            servers.set(DTA_ENTRY_KEY, dta);

            Path backup = backupIfExists(path);
            atomicWrite(path, root);
            String msg = backup != null
                ? "Installed dta MCP server to " + path + " (backup: " + backup + ")"
                : "Installed dta MCP server to " + path;
            log.info(msg);
            return Result.ok(path, backup, msg);
        } catch (Exception e) {
            return Result.fail("Failed to write " + path + ": " + e.getMessage());
        }
    }

    private static Result removeFromMcpJson(Path path) {
        if (path == null) {
            return Result.fail("Could not determine target config path.");
        }
        if (!Files.exists(path)) {
            return Result.ok(path, null, "No config file at " + path + " — nothing to remove.");
        }
        try {
            JsonNode parsed = mapper.readTree(path.toFile());
            if (!parsed.isObject()) {
                return Result.fail("Config file is not a JSON object: " + path);
            }
            ObjectNode root = (ObjectNode) parsed;
            JsonNode serversNode = root.get("mcpServers");
            if (serversNode == null || !serversNode.isObject() || !serversNode.has(DTA_ENTRY_KEY)) {
                return Result.ok(path, null, "No 'dta' entry in " + path + " — nothing to remove.");
            }
            ((ObjectNode) serversNode).remove(DTA_ENTRY_KEY);

            Path backup = backupIfExists(path);
            atomicWrite(path, root);
            return Result.ok(path, backup, "Removed dta MCP server from " + path);
        } catch (Exception e) {
            return Result.fail("Failed to update " + path + ": " + e.getMessage());
        }
    }

    private static ObjectNode readOrCreate(Path path) throws IOException {
        if (Files.exists(path) && Files.size(path) > 0) {
            JsonNode parsed = mapper.readTree(path.toFile());
            if (parsed.isObject()) return (ObjectNode) parsed;
            log.warn("{} is not a JSON object; will be replaced (a .bak copy is kept)", path);
        }
        return mapper.createObjectNode();
    }

    private static Path backupIfExists(Path path) throws IOException {
        if (!Files.exists(path)) return null;
        Path backup = path.resolveSibling(path.getFileName() + ".bak");
        Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    private static void atomicWrite(Path path, ObjectNode root) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        Files.writeString(tmp, json,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
        try {
            Files.move(tmp, path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Cross-volume etc. — fall back to non-atomic. Still safer than direct
            // overwrite because the .bak was made first.
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ========================================================================
    // Clipboard fallback
    // ========================================================================

    private static Result copyToClipboard(String snippet) {
        try {
            if (java.awt.GraphicsEnvironment.isHeadless()) {
                return Result.fail(
                    "Cannot copy to clipboard in a headless environment. Use 'print' instead, or pipe to pbcopy / xclip / clip.");
            }
            var toolkit = java.awt.Toolkit.getDefaultToolkit();
            var selection = new java.awt.datatransfer.StringSelection(snippet);
            toolkit.getSystemClipboard().setContents(selection, selection);
            return Result.ok(null, null, "Snippet copied to clipboard.");
        } catch (Exception e) {
            return Result.fail("Failed to copy to clipboard: " + e.getMessage());
        }
    }
}
