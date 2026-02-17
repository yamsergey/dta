package io.yamsergey.dta.cli.inspect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.yamsergey.dta.mcp.DaemonClient;
import io.yamsergey.dta.mcp.DaemonLauncher;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.concurrent.Callable;

@Command(name = "scroll-screenshot",
         description = "Capture scrolling/long screenshot of scrollable content via daemon.")
public class ScrollScreenshotCommand implements Callable<Integer> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Parameters(index = "0",
                description = "Output PNG file path (default: scroll-screenshot-TIMESTAMP.png)",
                arity = "0..1")
    private String outputPath;

    @Option(names = {"-o", "--output"},
            description = "Output PNG file path (alternative to positional argument).")
    private String outputPathOption;

    @Option(names = {"-d", "--device"},
            description = "Device serial number. If not specified, uses first available device.")
    private String deviceSerial;

    @Option(names = {"--view-id"},
            description = "Resource ID of scrollable view to capture.")
    private String viewId;

    @Option(names = {"--scroll-to-top"},
            description = "Scroll to top of content before starting capture.")
    private boolean scrollToTop;

    @Option(names = {"--max-captures"},
            description = "Maximum number of screenshots to capture (default: 30).")
    private Integer maxCaptures;

    @Override
    public Integer call() throws Exception {
        String finalOutputPath = outputPathOption != null ? outputPathOption : outputPath;
        if (finalOutputPath == null || finalOutputPath.isEmpty()) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            finalOutputPath = "scroll-screenshot-" + timestamp + ".png";
        }

        File outputFile = new File(finalOutputPath);
        File outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            if (!outputDir.mkdirs()) {
                System.err.println("Error: Failed to create output directory: " + outputDir.getAbsolutePath());
                return 1;
            }
        }

        try {
            DaemonClient daemon = DaemonLauncher.ensureDaemonRunning();

            System.err.println("Capturing scrolling screenshot...");
            if (deviceSerial != null) System.err.println("Device: " + deviceSerial);
            if (viewId != null) System.err.println("Target view: " + viewId);
            if (scrollToTop) System.err.println("Will scroll to top first");

            String json = daemon.scrollScreenshot(deviceSerial, viewId, scrollToTop,
                maxCaptures != null ? maxCaptures : 30);

            JsonNode node = mapper.readTree(json);
            if (node.has("imageBase64")) {
                byte[] imageBytes = Base64.getDecoder().decode(node.get("imageBase64").asText());
                Files.write(outputFile.toPath(), imageBytes);

                System.err.println("Success: Scrolling screenshot captured");
                System.err.println("Output: " + outputFile.getAbsolutePath());
                if (node.has("width") && node.has("height")) {
                    System.err.println("Dimensions: " + node.get("width").asInt() + "x" + node.get("height").asInt());
                }
                if (node.has("captures")) {
                    System.err.println("Captures: " + node.get("captures").asInt());
                }
                return 0;
            } else if (node.has("error")) {
                System.err.println("Error: " + node.get("error").asText());
                return 1;
            }
            System.err.println("Error: Unexpected response");
            return 1;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }
}
