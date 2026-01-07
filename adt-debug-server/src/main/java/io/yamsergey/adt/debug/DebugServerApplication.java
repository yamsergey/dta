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
 * <p>This server reads binary events from Android devices via ADB and
 * exposes them through a REST API with token-based authentication.</p>
 */
@SpringBootApplication
public class DebugServerApplication {

    private static ConfigurableApplicationContext context;

    // Static holders for configuration that SecurityConfig can access
    private static String staticAccessToken;
    private static String staticDeviceSerial;
    private static String staticPackageName;

    public static String getAccessToken() {
        return staticAccessToken;
    }

    public static String getDeviceSerial() {
        return staticDeviceSerial;
    }

    public static String getPackageName() {
        return staticPackageName;
    }

    /**
     * Starts the debug server on the specified port with the given configuration.
     *
     * @param port         the port to listen on
     * @param accessToken  the access token for authentication
     * @param deviceSerial optional device serial number
     * @param packageName  the package name of the app being debugged
     */
    public static void start(int port, String accessToken,
                             String deviceSerial, String packageName) {
        // Reset logback to avoid conflicts with other modules' logback.xml
        resetLogging();

        // Set static holders before context starts (SecurityConfig will use these)
        staticAccessToken = accessToken;
        staticDeviceSerial = deviceSerial != null ? deviceSerial : "";
        staticPackageName = packageName != null ? packageName : "";

        Map<String, Object> props = new HashMap<>();
        props.put("server.port", port);
        props.put("debug.access-token", accessToken);
        props.put("debug.device-serial", deviceSerial != null ? deviceSerial : "");
        props.put("debug.package-name", packageName != null ? packageName : "");

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
        SpringApplication.run(DebugServerApplication.class, args);
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
