package io.yamsergey.dta.sidekick.server;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for HTTP request body reading.
 *
 * <p>The original {@code InspectorServer.handleClient} read the request
 * body via {@code BufferedReader.read(char[], int, int)} sized to
 * {@code Content-Length}. {@code Content-Length} is in <b>bytes</b>;
 * {@code BufferedReader} yields <b>characters</b>. For pure ASCII this
 * happens to work (1 byte == 1 char). For multi-byte UTF-8 like emoji,
 * the request body decodes to fewer chars than bytes — the read loop
 * waits forever for chars that never arrive on the wire, hanging the
 * call until the daemon-side timeout fires.</p>
 *
 * <p>Reported failure: a user's interceptor script containing emoji
 * characters caused {@code interceptor_set} to time out; sidekick
 * eventually logged "interceptor script installed" once the socket was
 * closed. Removing emoji made it work. The fix below replaces the
 * char-based body read with byte-exact reading.</p>
 */
public class HttpRequestReaderTest {

    /**
     * Demonstrates the original char-based reader's failure mode: when
     * the body contains multi-byte UTF-8, the reader can't satisfy a
     * {@code char[]} of size {@code contentLength} because the bytes
     * decode to fewer chars. Without fresh data on the stream it would
     * block indefinitely; with our finite ByteArrayInputStream it
     * surfaces as "read fewer chars than expected", but the symptom on
     * a live socket is a hang.
     */
    @Test
    public void bufferedReaderCharLoop_underreadsMultiByteUtf8_demonstratingTheBug() throws IOException {
        // 🛠️ = U+1F6E0 (4 UTF-8 bytes, 2 UTF-16 chars) +
        //       U+FE0F (3 UTF-8 bytes, 1 UTF-16 char) = 7 bytes / 3 chars.
        String emoji = "🛠️"; // "🛠️"
        byte[] bytes = emoji.getBytes(StandardCharsets.UTF_8);
        int contentLength = bytes.length; // 7

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));

        char[] buf = new char[contentLength];
        int total = 0;
        while (total < contentLength) {
            int n = reader.read(buf, total, contentLength - total);
            if (n == -1) break; // EOF; on a live socket this would BLOCK instead.
            total += n;
        }

        // Buggy old behavior: only 3 chars decoded from 7 bytes; loop
        // exits via EOF in this finite test, but on a live socket no EOF
        // fires until the peer closes the connection.
        assertNotEquals("bug: char-based loop reads contentLength bytes-as-chars; " +
                        "on a live socket this manifests as a hang waiting for chars",
                contentLength, total);
        assertEquals("char count for 🛠️ in UTF-16 is 3 (surrogate pair + 1)", 3, total);
    }

    @Test
    public void readBody_decodesAsciiBytesUnchanged() throws IOException {
        String body = "function onRequest(req) { return req; }";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        InputStream wire = wireWithBody(bodyBytes);

        HttpRequestReader reader = new HttpRequestReader(wire);
        // skip request line + headers
        readRequestAndHeaders(reader);
        byte[] read = reader.readBody(bodyBytes.length);
        assertArrayEquals(bodyBytes, read);
        assertEquals(body, new String(read, StandardCharsets.UTF_8));
    }

    @Test
    public void readBody_preservesMultiByteUtf8() throws IOException {
        // Mix of ASCII + emoji (4-byte UTF-8) + accented chars (2-byte
        // UTF-8) — all of these fail the old char-based read on live
        // sockets.
        String body =
                "function onRequest(req) {\n" +
                "  log('🛠️ install ok'); // wrench-emoji\n" +
                "  log('résumé fête naïve');\n" +
                "  return req;\n" +
                "}\n";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        InputStream wire = wireWithBody(bodyBytes);

        HttpRequestReader reader = new HttpRequestReader(wire);
        readRequestAndHeaders(reader);
        byte[] read = reader.readBody(bodyBytes.length);

        assertArrayEquals("body bytes must round-trip exactly", bodyBytes, read);
        assertEquals(body, new String(read, StandardCharsets.UTF_8));
    }

    @Test
    public void readBody_doesNotHangWhenStreamHasOnlyContentLengthBytes() throws IOException {
        // The bug's user-visible symptom is "request hangs until daemon
        // timeout". Equivalent unit-test assertion: the reader must
        // return as soon as `contentLength` bytes are consumed, without
        // waiting for additional data.
        String body = "🛠️🛠️🛠️ hooks ready"; // ~30 bytes UTF-8, ~14 UTF-16 chars
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        InputStream wire = wireWithBody(bodyBytes);

        HttpRequestReader reader = new HttpRequestReader(wire);
        readRequestAndHeaders(reader);

        long start = System.nanoTime();
        byte[] read = reader.readBody(bodyBytes.length);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertArrayEquals(bodyBytes, read);
        // Reading from an in-memory byte buffer should be <50 ms even on
        // CI; if the implementation regresses to char-based it would
        // either underread (test fails on length) or, on a live socket,
        // hang until timeout.
        assertTrue("readBody completed in " + elapsedMs + "ms; should be near-instant",
                elapsedMs < 200);
    }

    @Test
    public void readLine_parsesAsciiHeadersTerminatedByCrlf() throws IOException {
        String request =
                "POST /interceptor HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Type: application/javascript\r\n" +
                "Content-Length: 42\r\n" +
                "\r\n";
        HttpRequestReader reader = new HttpRequestReader(
                new ByteArrayInputStream(request.getBytes(StandardCharsets.US_ASCII)));

        assertEquals("POST /interceptor HTTP/1.1", reader.readLine());
        assertEquals("Host: localhost", reader.readLine());
        assertEquals("Content-Type: application/javascript", reader.readLine());
        assertEquals("Content-Length: 42", reader.readLine());
        assertEquals("", reader.readLine());
    }

    @Test
    public void readBody_returnsExactlyContentLengthEvenIfStreamHasMore() throws IOException {
        // Defensive: a misbehaving peer that sent extra bytes after the
        // body shouldn't pollute future reads. (Doesn't happen on
        // sidekick today but we want the contract to be "give me
        // exactly N bytes".)
        byte[] body = "abc".getBytes(StandardCharsets.UTF_8);
        byte[] extra = "leftover-junk".getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write("POST / HTTP/1.1\r\nContent-Length: 3\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        wire.write(body);
        wire.write(extra);

        HttpRequestReader reader = new HttpRequestReader(new ByteArrayInputStream(wire.toByteArray()));
        readRequestAndHeaders(reader);
        byte[] read = reader.readBody(body.length);

        assertArrayEquals(body, read);
    }

    // -------- helpers --------

    private static InputStream wireWithBody(byte[] bodyBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("POST /interceptor HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));
        out.write(("Content-Length: " + bodyBytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.write(bodyBytes);
        return new ByteArrayInputStream(out.toByteArray());
    }

    private static void readRequestAndHeaders(HttpRequestReader reader) throws IOException {
        reader.readLine(); // request line
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            // discard
        }
    }
}
