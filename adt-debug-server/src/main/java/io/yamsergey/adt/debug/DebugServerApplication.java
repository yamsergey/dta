package io.yamsergey.adt.debug;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring Boot application for the ADT Debug Server.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li><b>Standalone mode</b>: Connect to a single app specified via command line.
 *       Usage: {@code java -jar debug-server.jar --port=8080 --device=emulator-5554 --package=com.example}</li>
 *   <li><b>MCP coordination mode</b>: Sessions registered by MCP servers via API.
 *       Usage: {@code java -jar debug-server.jar --mcp --port=8080}</li>
 * </ul>
 */
@SpringBootApplication
public class DebugServerApplication {

    private static ConfigurableApplicationContext context;

    // Static holders for configuration that SecurityConfig can access
    private static String staticAccessToken;
    private static String staticDeviceSerial;
    private static String staticPackageName;
    private static boolean staticMcpMode;

    public static String getAccessToken() {
        return staticAccessToken;
    }

    public static String getDeviceSerial() {
        return staticDeviceSerial;
    }

    public static String getPackageName() {
        return staticPackageName;
    }

    public static boolean isMcpMode() {
        return staticMcpMode;
    }

    /**
     * Starts the debug server in standalone mode with the given configuration.
     *
     * @param port         the port to listen on
     * @param accessToken  the access token for authentication
     * @param deviceSerial optional device serial number
     * @param packageName  the package name of the app being debugged
     */
    public static void start(int port, String accessToken,
                             String deviceSerial, String packageName) {
        start(port, accessToken, deviceSerial, packageName, false);
    }

    /**
     * Starts the debug server in MCP coordination mode.
     *
     * <p>In this mode, sessions are registered via the REST API by MCP servers.
     * No device/package is required at startup.
     *
     * @param port        the port to listen on
     * @param accessToken the access token for authentication (null to disable auth)
     */
    public static void startMcpMode(int port, String accessToken) {
        start(port, accessToken, null, null, true);
    }

    /**
     * Internal start method supporting both modes.
     */
    private static void start(int port, String accessToken,
                              String deviceSerial, String packageName, boolean mcpMode) {
        // Reset logback to avoid conflicts with other modules' logback.xml
        resetLogging();

        // Set static holders before context starts (SecurityConfig will use these)
        staticAccessToken = accessToken;
        staticDeviceSerial = deviceSerial != null ? deviceSerial : "";
        staticPackageName = packageName != null ? packageName : "";
        staticMcpMode = mcpMode;

        Map<String, Object> props = new HashMap<>();
        props.put("server.port", port);
        props.put("debug.access-token", accessToken != null ? accessToken : "");
        props.put("debug.device-serial", deviceSerial != null ? deviceSerial : "");
        props.put("debug.package-name", packageName != null ? packageName : "");
        props.put("debug.mcp-mode", mcpMode);

        // Disable banner and reduce startup noise
        props.put("spring.main.banner-mode", "off");
        props.put("logging.level.root", "WARN");
        props.put("logging.level.io.yamsergey.adt.debug", "INFO");

        // Disable logback status listener to avoid errors from conflicting configs
        props.put("logging.config", "classpath:logback-spring.xml");
        props.put("logging.logback.rollingpolicy.max-history", "0");

        context = new SpringApplicationBuilder(DebugServerApplication.class)
                .properties(props)
                .run();
    }

    /**
     * Stops the debug server.
     */
    public static void stop() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    /**
     * Generates a secure access token.
     */
    public static String generateToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return "sk_dbg_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static void main(String[] args) {
        // Parse command line arguments
        int port = 8080;
        String accessToken = null;
        String device = null;
        String packageName = null;
        boolean mcpMode = false;
        boolean noAuth = false;

        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring(7));
            } else if (arg.startsWith("--token=")) {
                accessToken = arg.substring(8);
            } else if (arg.startsWith("--device=")) {
                device = arg.substring(9);
            } else if (arg.startsWith("--package=")) {
                packageName = arg.substring(10);
            } else if (arg.equals("--mcp")) {
                mcpMode = true;
            } else if (arg.equals("--no-auth")) {
                noAuth = true;
            } else if (arg.equals("--help") || arg.equals("-h")) {
                printUsage();
                return;
            }
        }

        // In MCP mode, auth is disabled by default (can override with --token)
        if (mcpMode && accessToken == null && !noAuth) {
            // MCP mode without explicit token = no auth
            noAuth = true;
        }

        // Generate token if auth is enabled and no token provided
        if (!noAuth && accessToken == null) {
            accessToken = generateToken();
            System.out.println("Generated access token: " + accessToken);
        }

        if (mcpMode) {
            System.out.println("Starting debug server in MCP coordination mode on port " + port);
            startMcpMode(port, noAuth ? null : accessToken);
        } else {
            System.out.println("Starting debug server in standalone mode on port " + port);
            start(port, accessToken, device, packageName);
        }
    }

    private static void printUsage() {
        System.out.println("ADT Debug Server");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  Standalone mode: debug-server --port=8080 --device=emulator-5554 --package=com.example");
        System.out.println("  MCP mode:        debug-server --mcp --port=8080");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --port=PORT      Server port (default: 8080)");
        System.out.println("  --device=SERIAL  Device serial number (standalone mode)");
        System.out.println("  --package=PKG    Package name (standalone mode)");
        System.out.println("  --mcp            Enable MCP coordination mode");
        System.out.println("  --token=TOKEN    Access token for authentication");
        System.out.println("  --no-auth        Disable authentication");
        System.out.println("  --help, -h       Show this help message");
    }

    /**
     * Resets the logging system to use our configuration.
     */
    private static void resetLogging() {
        try {
            // Get the logback LoggerContext and reset it
            org.slf4j.ILoggerFactory factory = org.slf4j.LoggerFactory.getILoggerFactory();
            if (factory instanceof ch.qos.logback.classic.LoggerContext) {
                ch.qos.logback.classic.LoggerContext loggerContext = (ch.qos.logback.classic.LoggerContext) factory;
                loggerContext.reset();

                // Configure a simple console appender
                ch.qos.logback.classic.encoder.PatternLayoutEncoder encoder = new ch.qos.logback.classic.encoder.PatternLayoutEncoder();
                encoder.setContext(loggerContext);
                encoder.setPattern("%d{HH:mm:ss} %-5level %logger{36} - %msg%n");
                encoder.start();

                ch.qos.logback.core.ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                        new ch.qos.logback.core.ConsoleAppender<>();
                appender.setContext(loggerContext);
                appender.setEncoder(encoder);
                appender.start();

                ch.qos.logback.classic.Logger rootLogger = loggerContext.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
                rootLogger.detachAndStopAllAppenders();
                rootLogger.addAppender(appender);
                rootLogger.setLevel(ch.qos.logback.classic.Level.WARN);
            }
        } catch (Exception e) {
            // Ignore - logging will work with defaults
        }
    }
}
