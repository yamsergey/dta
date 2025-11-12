package io.yamsergey.adt.tools.android.vector;

import io.yamsergey.adt.tools.sugar.Failure;
import io.yamsergey.adt.tools.sugar.Result;
import io.yamsergey.adt.tools.sugar.Success;
import lombok.Builder;
import lombok.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

/**
 * Converts Android Vector Drawable XML resources to PNG images.
 *
 * <p>This converter takes an Android vector drawable XML file and generates a PNG image
 * using Apache Batik's SVG transcoding capabilities. The Android vector drawable format
 * is converted to SVG format before transcoding.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * Result&lt;File&gt; result = VectorDrawableConverter.builder()
 *     .inputFile(new File("ic_launcher.xml"))
 *     .outputFile(new File("ic_launcher.png"))
 *     .width(512)
 *     .height(512)
 *     .build()
 *     .convert();
 * </pre>
 */
@Builder
public class VectorDrawableConverter {

    @NonNull
    private final File inputFile;

    @NonNull
    private final File outputFile;

    @Builder.Default
    private final int width = 512;

    @Builder.Default
    private final int height = 512;

    /**
     * Converts the vector drawable to PNG format.
     *
     * @return Result containing the output PNG file on success, or error details on failure
     */
    public Result<File> convert() {
        if (!inputFile.exists()) {
            return Result.<File>failure()
                .description("Input file does not exist: " + inputFile.getAbsolutePath())
                .build();
        }

        if (!inputFile.isFile()) {
            return Result.<File>failure()
                .description("Input path is not a file: " + inputFile.getAbsolutePath())
                .build();
        }

        try {
            // Parse and convert Android vector drawable to SVG
            String svgContent = VectorDrawableParser.parseAndConvertToSvg(inputFile);

            // Create a temporary SVG file
            File tempSvgFile = createTempSvgFile(svgContent);

            try {
                // Transcode SVG to PNG
                Result<File> transcodeResult = transcodeSvgToPng(tempSvgFile, outputFile, width, height);
                return transcodeResult;
            } finally {
                // Clean up temporary SVG file
                if (tempSvgFile.exists()) {
                    tempSvgFile.delete();
                }
            }
        } catch (Exception e) {
            return Result.<File>failure()
                .cause(e)
                .description("Failed to convert vector drawable: " + e.getMessage())
                .build();
        }
    }

    /**
     * Creates a temporary SVG file from the SVG content string.
     */
    private File createTempSvgFile(String svgContent) throws IOException {
        String tmpDir = System.getenv("TMPDIR");
        if (tmpDir == null || tmpDir.isEmpty()) {
            tmpDir = System.getProperty("java.io.tmpdir");
        }

        File tempFile = File.createTempFile("vector_drawable_", ".svg", new File(tmpDir));
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(svgContent.getBytes("UTF-8"));
        }
        return tempFile;
    }

    /**
     * Transcodes an SVG file to PNG using Apache Batik.
     */
    private Result<File> transcodeSvgToPng(File svgFile, File pngFile, int width, int height) {
        try {
            // Create the PNG transcoder
            PNGTranscoder transcoder = new PNGTranscoder();

            // Set transcoding hints
            transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) width);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) height);

            // Setup input and output
            try (InputStream inputStream = new FileInputStream(svgFile);
                 OutputStream outputStream = new FileOutputStream(pngFile)) {

                TranscoderInput input = new TranscoderInput(inputStream);
                TranscoderOutput output = new TranscoderOutput(outputStream);

                // Perform the transcoding
                transcoder.transcode(input, output);
            }

            return Result.<File>success()
                .value(pngFile)
                .description("Successfully converted to PNG: " + pngFile.getAbsolutePath())
                .build();

        } catch (TranscoderException e) {
            return Result.<File>failure()
                .cause(e)
                .description("Transcoding failed: " + e.getMessage())
                .build();
        } catch (IOException e) {
            return Result.<File>failure()
                .cause(e)
                .description("I/O error during transcoding: " + e.getMessage())
                .build();
        }
    }
}
