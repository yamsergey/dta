package io.yamsergey.dta.sidekick.network;

import android.content.Context;
import io.yamsergey.dta.sidekick.SidekickLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Handles storage of large HTTP bodies to disk using content-addressable storage.
 *
 * <p>Bodies are stored using their content hash as the filename, enabling automatic
 * deduplication. Multiple requests with identical response bodies share a single
 * stored file.</p>
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
     * Stores a text body to disk and returns a reference.
     *
     * <p>Uses content-addressable storage: the file is named by its content hash.
     * If an identical body is already stored, returns a reference to the existing file.</p>
     *
     * @param transactionId the transaction ID (used for logging only)
     * @param type "request" or "response" (used for logging only)
     * @param body the body content
     * @param contentType the content type
     * @return a reference to the stored body
     */
    public BodyReference store(String transactionId, String type, String body, String contentType) {
        if (body == null || body.isEmpty()) {
            return null;
        }

        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String hash = computeHash(bytes);
            if (hash == null) {
                return null;
            }

            String filename = hash + ".bin";
            File file = new File(storageDir, filename);

            // Content-addressable: only write if file doesn't exist
            if (!file.exists()) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(bytes);
                }
                SidekickLog.d(TAG, "Stored body: " + hash + " (" + bytes.length + " bytes)");
            } else {
                SidekickLog.d(TAG, "Deduplicated body: " + hash + " (already exists)");
            }

            // Create reference
            return new BodyReference(
                    file.getAbsolutePath(),
                    bytes.length,
                    contentType,
                    createPreview(body),
                    hash
            );

        } catch (IOException e) {
            SidekickLog.e(TAG, "Failed to store body for transaction: " + transactionId, e);
            return null;
        }
    }

    /**
     * Stores binary body data to disk and returns a reference.
     *
     * <p>Uses content-addressable storage: the file is named by its content hash.
     * If an identical body is already stored, returns a reference to the existing file.</p>
     *
     * @param transactionId the transaction ID (used for logging only)
     * @param type "request" or "response" (used for logging only)
     * @param data the body bytes
     * @param contentType the content type
     * @return a reference to the stored body
     */
    public BodyReference store(String transactionId, String type, byte[] data, String contentType) {
        if (data == null || data.length == 0) {
            return null;
        }

        try {
            String hash = computeHash(data);
            if (hash == null) {
                return null;
            }

            String filename = hash + ".bin";
            File file = new File(storageDir, filename);

            // Content-addressable: only write if file doesn't exist
            if (!file.exists()) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(data);
                }
                SidekickLog.d(TAG, "Stored binary body: " + hash + " (" + data.length + " bytes)");
            } else {
                SidekickLog.d(TAG, "Deduplicated binary body: " + hash + " (already exists)");
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
                    hash
            );

        } catch (IOException e) {
            SidekickLog.e(TAG, "Failed to store body for transaction: " + transactionId, e);
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
            SidekickLog.e(TAG, "Failed to read body: " + file, e);
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
            SidekickLog.e(TAG, "Failed to read body: " + file, e);
            return null;
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
        SidekickLog.d(TAG, "Cleared all stored bodies");
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

    /**
     * Gets the number of stored body files.
     */
    public int getFileCount() {
        File[] files = storageDir.listFiles();
        return files != null ? files.length : 0;
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

    /**
     * Computes SHA-256 hash of data and returns hex string.
     * Uses full 32-byte hash for reliable content addressing.
     */
    private String computeHash(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            SidekickLog.e(TAG, "Failed to compute hash", e);
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
