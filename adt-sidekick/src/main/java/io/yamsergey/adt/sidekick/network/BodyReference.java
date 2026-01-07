package io.yamsergey.adt.sidekick.network;

/**
 * A reference to a body stored on disk.
 *
 * <p>When an HTTP body exceeds the inline size threshold, it's stored
 * to disk and replaced with a BodyReference. This allows efficient
 * handling of large bodies without consuming excessive memory.</p>
 *
 * <p>The reference includes:</p>
 * <ul>
 *   <li>Path to the stored file</li>
 *   <li>Size in bytes</li>
 *   <li>Content type</li>
 *   <li>Truncated preview (first 1KB) for quick inspection</li>
 *   <li>Hash for integrity verification</li>
 * </ul>
 */
public final class BodyReference {

    private final String path;
    private final long size;
    private final String contentType;
    private final String preview;
    private final String hash;

    public BodyReference(String path, long size, String contentType, String preview, String hash) {
        this.path = path;
        this.size = size;
        this.contentType = contentType;
        this.preview = preview;
        this.hash = hash;
    }

    /**
     * Gets the file path where the body is stored.
     */
    public String getPath() {
        return path;
    }

    /**
     * Gets the size of the body in bytes.
     */
    public long getSize() {
        return size;
    }

    /**
     * Gets the content type of the body.
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * Gets a truncated preview of the body (first 1KB).
     *
     * @return preview text, or null for binary content
     */
    public String getPreview() {
        return preview;
    }

    /**
     * Gets the hash of the body content (SHA-256, first 16 hex chars).
     */
    public String getHash() {
        return hash;
    }

    /**
     * Returns a human-readable size string.
     */
    public String getSizeString() {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        } else {
            return String.format("%.1f MB", size / (1024.0 * 1024));
        }
    }

    @Override
    public String toString() {
        return "BodyReference{" +
                "size=" + getSizeString() +
                ", contentType='" + contentType + '\'' +
                ", hash='" + hash + '\'' +
                '}';
    }
}
