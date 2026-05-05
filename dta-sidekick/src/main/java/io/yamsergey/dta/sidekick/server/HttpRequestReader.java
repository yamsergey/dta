package io.yamsergey.dta.sidekick.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Byte-exact HTTP request reader for the Unix-domain inspector server.
 *
 * <p>Replaces the previous {@code BufferedReader}-based logic, which
 * sized a {@code char[]} to {@code Content-Length} (a byte count) and
 * tried to fill it via {@code reader.read(char[], ...)} (a char count).
 * For ASCII the two were equal so it happened to work; for multi-byte
 * UTF-8 (e.g. emoji in an interceptor script) the byte stream decoded
 * to fewer chars than {@code Content-Length}, so the read loop blocked
 * waiting for chars that never arrived. Result: every {@code POST}
 * with a multi-byte body hung until the daemon-side timeout.</p>
 *
 * <p>This class reads HTTP at the byte level: {@link #readLine()}
 * returns one CRLF-terminated line (decoded as US-ASCII because HTTP
 * request lines and headers are ASCII per RFC 7230), and
 * {@link #readBody(int)} returns exactly {@code n} bytes (or fewer at
 * EOF) without any character-set decoding. Callers handle the body
 * encoding themselves by calling {@code new String(bytes, UTF_8)} or
 * passing the raw bytes through to a downstream consumer.</p>
 *
 * <p>Not thread-safe. One instance per accepted client.</p>
 */
public final class HttpRequestReader {

    private final InputStream in;

    public HttpRequestReader(InputStream in) {
        this.in = in;
    }

    /**
     * Reads one CRLF-terminated line and returns it as US-ASCII (HTTP
     * request lines and header fields are ASCII per RFC 7230 §3.2.4 —
     * non-ASCII bytes in headers are obs-text and we don't accept them).
     * Returns {@code null} at EOF before any bytes were read; returns
     * an empty string for an immediate CRLF (the blank line that
     * separates headers from body).
     */
    public String readLine() throws IOException {
        ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                int next = in.read();
                if (next == '\n') {
                    return lineBuffer.toString(StandardCharsets.US_ASCII.name());
                }
                // Bare \r — keep both bytes literally; HTTP spec
                // forbids this but we don't go out of our way to
                // reject malformed input here.
                lineBuffer.write(b);
                if (next == -1) break;
                lineBuffer.write(next);
            } else if (b == '\n') {
                // Bare LF (some clients) — accept as line terminator.
                return lineBuffer.toString(StandardCharsets.US_ASCII.name());
            } else {
                lineBuffer.write(b);
            }
        }
        if (lineBuffer.size() == 0) return null;
        return lineBuffer.toString(StandardCharsets.US_ASCII.name());
    }

    /**
     * Reads exactly {@code n} bytes from the underlying stream and
     * returns them. Returns a shorter array at EOF — callers can detect
     * truncation by comparing length to {@code n}. {@code n == 0}
     * returns an empty array without touching the stream.
     */
    public byte[] readBody(int n) throws IOException {
        if (n <= 0) return new byte[0];
        byte[] buf = new byte[n];
        int total = 0;
        while (total < n) {
            int r = in.read(buf, total, n - total);
            if (r == -1) break;
            total += r;
        }
        if (total == n) return buf;
        byte[] truncated = new byte[total];
        System.arraycopy(buf, 0, truncated, 0, total);
        return truncated;
    }
}
