package io.yamsergey.adt.tools.android.cli;

import io.yamsergey.adt.tools.android.vector.VectorDrawableConverter;
import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.util.concurrent.Callable;

/**
 * CLI command for converting Android vector drawable resources to PNG images.
 *
 * <p>This command provides a command-line interface for the vector-to-PNG conversion
 * functionality. It supports customizable output dimensions and can process individual
 * vector drawable XML files.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * # Convert with default size (512x512)
 * android-tools drawable -i ic_launcher.xml -o ic_launcher.png
 *
 * # Convert with custom size
 * android-tools drawable -i ic_launcher.xml -o ic_launcher.png -w 1024 -h 1024
 *
 * # Convert with density-based sizing
 * android-tools drawable -i ic_launcher.xml -o ic_launcher.png --density xxxhdpi
 * </pre>
 */
@Command(name = "drawable", description = "Convert Android vector drawable XML to PNG image.")
public class DrawableCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Input vector drawable XML file path", paramLabel = "INPUT")
    private String inputPath;

    @Option(names = {"-o", "--output"}, description = "Output PNG file path", required = true)
    private String outputPath;

    @Option(names = {"-w", "--width"}, description = "Output image width in pixels (default: 512)")
    private Integer width;

    @Option(names = {"-h", "--height"}, description = "Output image height in pixels (default: 512)")
    private Integer height;

    @Option(names = {"--density"}, description = "Android density qualifier (ldpi, mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi). Overrides width/height.")
    private String density;

    @Override
    public Integer call() throws Exception {
        // Validate input file
        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            System.err.println("Error: Input file does not exist: " + inputPath);
            return 1;
        }

        if (!inputFile.isFile()) {
            System.err.println("Error: Input path is not a file: " + inputPath);
            return 1;
        }

        // Determine output dimensions
        int outputWidth = 512;
        int outputHeight = 512;

        if (density != null && !density.isEmpty()) {
            // Convert density to pixel size
            int densitySize = getDensitySize(density);
            if (densitySize == -1) {
                System.err.println("Error: Invalid density qualifier: " + density);
                System.err.println("Valid values: ldpi, mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi");
                return 1;
            }
            outputWidth = densitySize;
            outputHeight = densitySize;
        } else {
            // Use custom dimensions if provided
            if (width != null && width > 0) {
                outputWidth = width;
            }
            if (height != null && height > 0) {
                outputHeight = height;
            }
        }

        // Validate output path
        File outputFile = new File(outputPath);

        // Create output directory if it doesn't exist
        File outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            if (!outputDir.mkdirs()) {
                System.err.println("Error: Failed to create output directory: " + outputDir.getAbsolutePath());
                return 1;
            }
        }

        // Perform conversion
        System.out.println("Converting vector drawable to PNG...");
        System.out.println("Input:  " + inputFile.getAbsolutePath());
        System.out.println("Output: " + outputFile.getAbsolutePath());
        System.out.println("Size:   " + outputWidth + "x" + outputHeight);

        Result<File> result = VectorDrawableConverter.builder()
                .inputFile(inputFile)
                .outputFile(outputFile)
                .width(outputWidth)
                .height(outputHeight)
                .build()
                .convert();

        return switch (result) {
            case Success<File> success -> {
                String description = success.description() != null ? success.description() : "PNG generated successfully";
                System.out.println("Success: " + description);
                yield 0;
            }
            case Failure<File> failure -> {
                String description = failure.description() != null ? failure.description() : "Conversion failed";
                System.err.println("Error: " + description);
                yield 1;
            }
            default -> {
                System.err.println("Error: Unknown result type");
                yield 1;
            }
        };
    }

    /**
     * Converts Android density qualifier to pixel size for launcher icons.
     *
     * @param density The density qualifier (ldpi, mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi)
     * @return The pixel size for the density, or -1 if invalid
     */
    private int getDensitySize(String density) {
        return switch (density.toLowerCase()) {
            case "ldpi" -> 36;
            case "mdpi" -> 48;
            case "hdpi" -> 72;
            case "xhdpi" -> 96;
            case "xxhdpi" -> 144;
            case "xxxhdpi" -> 192;
            default -> -1;
        };
    }
}
