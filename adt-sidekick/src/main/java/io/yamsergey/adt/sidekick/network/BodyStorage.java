package io.yamsergey.adt.sidekick.network;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Handles storage of large HTTP bodies to disk.
 *
 * <p>When a body exceeds the inline threshold, it's stored to a file
 * and replaced with a {@link BodyReference} containing the file path
 * and metadata.</p>
 *
 * <p>Bodies are stored in the app's cache directory under {@code adt-bodies/}.</p>
 */
public final class BodyStorage {

    private static final String TAG = "BodyStorage";
    private static final String BODIES_DIR = "adt-bodies";
    private static final int PREVIEW_SIZE = 1024; // 1KB preview for large bodies

    private static volatile BodyStorage instance;
    private final File storageDir;

    private BodyStorage(Context context) {
        this.storageDir = new File(context.getCacheDir(), BODIES_DIR);
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
    }

    public static void init(Context context) {
        if (instance == null) {
            synchronized (BodyStorage.class) {
                if (instance == null) {
                    instance = new BodyStorage(context.getApplicationContext());
                }
            }
        }
    }

    public static BodyStorage getInstance() {
        return instance;
    }

    /**
     * Stores a body to disk and returns a reference.
     *
     * @param transactionId the transaction ID
     * @param type "request" or "response"
     * @param body the body content
     * @param contentType the content type
     * @return a reference to the stored body
     */
    public BodyReference store(String transactionId, String type, String body, String contentType) {
        if (body == null || body.isEmpty()) {
            return null;
        }

        String filename = transactionId + "-" + type + ".bin";
        File file = new File(storageDir, filename);

        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

            // Write to file
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(bytes);
            }

            // Create reference
            return new BodyReference(
                    file.getAbsolutePath(),
                    bytes.length,
                    contentType,
                    createPreview(body),
                    computeHash(bytes)
            );

        } catch (IOException e) {
            Log.e(TAG, "Failed to store body: " + file, e);
            return null;
        }
    }

    /**
     * Stores binary body data to disk.
     *
     * @param transactionId the transaction ID
     * @param type "request" or "response"
     * @param data the body bytes
     * @param contentType the content type
     * @return a reference to the stored body
     */
    public BodyReference store(String transactionId, String type, byte[] data, String contentType) {
        if (data == null || data.length == 0) {
            return null;
        }

        String filename = transactionId + "-" + type + ".bin";
        File file = new File(storageDir, filename);

        try {
            // Write to file
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data);
            }

            // Create preview (for text content)
            String preview = null;
            if (isTextContent(contentType)) {
                int previewLen = Math.min(data.length, PREVIEW_SIZE);
                preview = new String(data, 0, previewLen, StandardCharsets.UTF_8);
                if (data.length > PREVIEW_SIZE) {
                    preview += "...";
                }
            }

            // Create reference
            return new BodyReference(
                    file.getAbsolutePath(),
                    data.length,
                    contentType,
                    preview,
                    computeHash(data)
            );

        } catch (IOException e) {
            Log.e(TAG, "Failed to store body: " + file, e);
            return null;
        }
    }

    /**
     * Reads a body from disk using a reference.
     *
     * @param ref the body reference
     * @return the body content as string, or null if not found
     */
    public String read(BodyReference ref) {
        if (ref == null || ref.getPath() == null) {
            return null;
        }

        File file = new File(ref.getPath());
        if (!file.exists()) {
            return null;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            fis.read(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read body: " + file, e);
            return null;
        }
    }

    /**
     * Reads body bytes from disk using a reference.
     *
     * @param ref the body reference
     * @return the body bytes, or null if not found
     */
    public byte[] readBytes(BodyReference ref) {
        if (ref == null || ref.getPath() == null) {
            return null;
        }

        File file = new File(ref.getPath());
        if (!file.exists()) {
            return null;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            fis.read(bytes);
            return bytes;
        } catch (IOException e) {
            Log.e(TAG, "Failed to read body: " + file, e);
            return null;
        }
    }

    /**
     * Deletes stored bodies for a transaction.
     *
     * @param transactionId the transaction ID
     */
    public void delete(String transactionId) {
        File requestFile = new File(storageDir, transactionId + "-request.bin");
        File responseFile = new File(storageDir, transactionId + "-response.bin");

        if (requestFile.exists()) {
            requestFile.delete();
        }
        if (responseFile.exists()) {
            responseFile.delete();
        }
    }

    /**
     * Clears all stored bodies.
     */
    public void clear() {
        File[] files = storageDir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
    }

    /**
     * Gets the total size of stored bodies in bytes.
     */
    public long getTotalSize() {
        long total = 0;
        File[] files = storageDir.listFiles();
        if (files != null) {
            for (File file : files) {
                total += file.length();
            }
        }
        return total;
    }

    private String createPreview(String body) {
        if (body == null) {
            return null;
        }
        if (body.length() <= PREVIEW_SIZE) {
            return body;
        }
        return body.substring(0, PREVIEW_SIZE) + "...";
    }

    private String computeHash(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) { // First 8 bytes = 16 hex chars
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isTextContent(String contentType) {
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase();
        return lower.contains("text/") ||
                lower.contains("json") ||
                lower.contains("xml") ||
                lower.contains("javascript") ||
                lower.contains("x-www-form-urlencoded");
    }
}
