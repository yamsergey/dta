package io.yamsergey.adt.cli.inspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.yamsergey.adt.tools.android.inspect.ViewHierarchy;
import io.yamsergey.adt.tools.android.inspect.ViewHierarchyDumper;
import io.yamsergey.adt.tools.android.inspect.ViewHierarchyParser;
import io.yamsergey.adt.tools.android.inspect.ViewNode;
import io.yamsergey.adt.tools.android.inspect.ViewNodeFilter;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI command for dumping the current UI layout hierarchy from an Android device.
 *
 * <p>This command captures the view hierarchy from a connected Android device or emulator
 * using UIAutomator. The hierarchy can be output in XML or JSON format, making it easy for
 * coding agents and automation tools to process.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * # Dump layout to stdout (XML)
 * adt-cli inspect layout
 *
 * # Dump as JSON (agent-friendly)
 * adt-cli inspect layout --format json
 *
 * # Save JSON to file
 * adt-cli inspect layout --format json -o hierarchy.json
 *
 * # Dump from specific device
 * adt-cli inspect layout -d emulator-5554 --format json
 *
 * # Use compressed format (faster, less detail)
 * adt-cli inspect layout --compressed -o hierarchy.xml
 *
 * # Specify custom ADB path
 * adt-cli inspect layout --adb-path /custom/path/to/adb --format json
 * </pre>
 */
@Command(name = "layout",
         description = "Dump current UI layout hierarchy from Android device.")
public class LayoutCommand implements Callable<Integer> {

    @Option(names = {"-o", "--output"},
            description = "Output file path. If not specified, prints to stdout.")
    private String outputPath;

    @Option(names = {"-f", "--format"},
            description = "Output format: xml (default) or json (agent-friendly).")
    private String format = "xml";

    @Option(names = {"-d", "--device"},
            description = "Device serial number. If not specified, uses first available device.")
    private String deviceSerial;

    @Option(names = {"--compressed"},
            description = "Use compressed hierarchy format (faster but less detailed).")
    private boolean compressed;

    @Option(names = {"--timeout"},
            description = "Timeout in seconds for ADB commands (default: 30).")
    private Integer timeoutSeconds;

    @Option(names = {"--adb-path"},
            description = "Path to ADB executable. If not specified, uses 'adb' from PATH.")
    private String adbPath;

    @Option(names = {"--pretty"},
            description = "Pretty-print JSON output (default: true).")
    private boolean pretty = true;

    // Filter options
    @Option(names = {"--text"},
            description = "Filter nodes by text content (substring match, or 'regex:pattern' for regex).")
    private String textFilter;

    @Option(names = {"--class"},
            description = "Filter nodes by class name (substring match, or 'regex:pattern' for regex).")
    private String classFilter;

    @Option(names = {"--resource-id"},
            description = "Filter nodes by resource ID (substring match, or 'regex:pattern' for regex).")
    private String resourceIdFilter;

    @Option(names = {"--content-desc"},
            description = "Filter nodes by content description (substring match, or 'regex:pattern' for regex).")
    private String contentDescFilter;

    @Option(names = {"--clickable"},
            description = "Only return clickable nodes.")
    private boolean clickableOnly;

    @Option(names = {"--with-text"},
            description = "Only return nodes that have non-empty text content.")
    private boolean withTextOnly;

    @Option(names = {"--include-parents"},
            description = "Include parent chain for each matching node (useful for Compose apps).")
    private boolean includeParents;

    @Override
    public Integer call() throws Exception {
        // Validate format
        if (!format.equalsIgnoreCase("xml") && !format.equalsIgnoreCase("json")) {
            System.err.println("Error: Invalid format '" + format + "'. Must be 'xml' or 'json'.");
            return 1;
        }

        // Build the dumper
        ViewHierarchyDumper.ViewHierarchyDumperBuilder builder = ViewHierarchyDumper.builder()
                .compressed(compressed);

        if (deviceSerial != null && !deviceSerial.isEmpty()) {
            builder.deviceSerial(deviceSerial);
        }

        // Don't set output file yet - we'll handle it after format conversion
        if (timeoutSeconds != null && timeoutSeconds > 0) {
            builder.timeoutSeconds(timeoutSeconds);
        }

        if (adbPath != null && !adbPath.isEmpty()) {
            builder.adbPath(adbPath);
        }

        // Execute the dump
        System.err.println("Dumping UI layout hierarchy...");
        if (deviceSerial != null) {
            System.err.println("Device: " + deviceSerial);
        }
        if (compressed) {
            System.err.println("Format: compressed");
        }
        System.err.println("Output format: " + format.toUpperCase());

        Result<ViewHierarchy> result = builder.build().dump();

        return switch (result) {
            case Success<ViewHierarchy> success -> {
                ViewHierarchy hierarchy = success.value();
                String xmlContent = hierarchy.getXmlContent();

                // Build filter if any filter options specified
                ViewNodeFilter filter = ViewNodeFilter.builder()
                        .textPattern(textFilter)
                        .classPattern(classFilter)
                        .resourceIdPattern(resourceIdFilter)
                        .contentDescPattern(contentDescFilter)
                        .clickableOnly(clickableOnly)
                        .withTextOnly(withTextOnly)
                        .includeParents(includeParents)
                        .build();

                // Convert to requested format
                String outputContent;
                if (format.equalsIgnoreCase("json")) {
                    // Parse XML to ViewNode tree
                    Result<ViewNode> parseResult = ViewHierarchyParser.parse(xmlContent);
                    if (parseResult instanceof Failure<ViewNode> parseFailure) {
                        System.err.println("Error: " + parseFailure.description());
                        yield 1;
                    }

                    ViewNode rootNode = ((Success<ViewNode>) parseResult).value();

                    // Convert to JSON
                    ObjectMapper mapper = new ObjectMapper();
                    if (pretty) {
                        mapper.enable(SerializationFeature.INDENT_OUTPUT);
                    }

                    // Apply filter if any filters are specified
                    if (filter.hasFilters()) {
                        List<ViewNodeFilter.FilteredNode> filteredNodes = filter.filter(rootNode);
                        System.err.println("Filter matched " + filteredNodes.size() + " node(s)");
                        outputContent = mapper.writeValueAsString(filteredNodes);
                    } else {
                        outputContent = mapper.writeValueAsString(rootNode);
                    }
                } else {
                    if (filter.hasFilters()) {
                        System.err.println("Warning: Filters are only supported with JSON format. Use --format json to enable filtering.");
                    }
                    outputContent = xmlContent;
                }

                // Output to file or stdout
                if (outputPath != null && !outputPath.isEmpty()) {
                    File outputFile = new File(outputPath);

                    // Create output directory if needed
                    File outputDir = outputFile.getParentFile();
                    if (outputDir != null && !outputDir.exists()) {
                        if (!outputDir.mkdirs()) {
                            System.err.println("Error: Failed to create output directory: " + outputDir.getAbsolutePath());
                            yield 1;
                        }
                    }

                    Files.writeString(outputFile.toPath(), outputContent);
                    System.err.println("Success: Layout hierarchy dumped successfully");
                    System.err.println("Output: " + outputFile.getAbsolutePath());
                } else {
                    // Print to stdout
                    System.out.println(outputContent);
                }

                yield 0;
            }
            case Failure<ViewHierarchy> failure -> {
                String description = failure.description() != null ? failure.description() : "Failed to dump layout hierarchy";
                System.err.println("Error: " + description);
                yield 1;
            }
            default -> {
                System.err.println("Error: Unknown result type");
                yield 1;
            }
        };
    }
}
