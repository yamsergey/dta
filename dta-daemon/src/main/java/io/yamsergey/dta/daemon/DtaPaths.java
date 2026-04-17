package io.yamsergey.dta.daemon;

import java.nio.file.Path;

/**
 * Central location for DTA's user-level config/state directory.
 *
 * <p>Follows platform conventions:</p>
 * <ul>
 *   <li><b>Windows:</b> {@code %APPDATA%\dta} (e.g. {@code C:\Users\X\AppData\Roaming\dta})</li>
 *   <li><b>macOS / Linux / other:</b> {@code $XDG_CONFIG_HOME/dta} (defaults to {@code ~/.config/dta})</li>
 * </ul>
 */
public final class DtaPaths {

    private DtaPaths() {}

    public static Path configDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isEmpty()) {
                return Path.of(appData, "dta");
            }
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isEmpty()) {
            return Path.of(xdg, "dta");
        }
        return Path.of(System.getProperty("user.home"), ".config", "dta");
    }

    public static Path daemonStateFile() {
        return configDir().resolve("daemon.json");
    }
}
