package io.yamsergey.dta.sidekick.server;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end regression for the multi-byte UTF-8 body hang.
 *
 * <p>Spins up a tiny TCP server in a background thread that uses
 * exactly the same code path as {@code InspectorServer.handleClient}'s
 * body read (now via {@link HttpRequestReader}), sends a real HTTP
 * request over a real socket with an emoji-laden body, and verifies
 * the server returns the body bytes in the response — all within a
 * tight deadline. With the old char-based reader the server would
 * block waiting for chars that never arrive; this test would time out
 * (which is exactly the user-visible symptom we're guarding
 * against).</p>
 */
public class InspectorServerBodyDecodingTest {

    @Test
    public void serverEchoesEmojiBodyWithoutHanging() throws Exception {
        // Body that's known to break the old char-based reader: emoji
        // + accented latin chars. UTF-8 bytes > UTF-16 chars.
        String script =
                "function onRequest(req) {\n" +
                "  log('🛠️ install ok');\n" +
                "  log('résumé fête naïve');\n" +
                "  return req;\n" +
                "}\n";
        byte[] scriptBytes = script.getBytes(StandardCharsets.UTF_8);
        // Sanity: bytes > chars is what makes this exercise the bug.
        assertTrue("test premise: UTF-8 body must be longer than UTF-16 char count",
                scriptBytes.length > script.length());

        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = server.getLocalPort();

            CompletableFuture<byte[]> serverEchoed = CompletableFuture.supplyAsync(() -> {
                try (Socket client = server.accept()) {
                    HttpRequestReader reader = new HttpRequestReader(client.getInputStream());
                    String requestLine = reader.readLine();
                    int contentLength = 0;
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            contentLength = Integer.parseInt(line.substring(15).trim());
                        }
                    }
                    byte[] body = reader.readBody(contentLength);

                    // Echo body back as a 200 response so the test can
                    // verify the server actually got the right bytes.
                    OutputStream out = client.getOutputStream();
                    String headers =
                            "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/octet-stream\r\n" +
                            "Content-Length: " + body.length + "\r\n" +
                            "\r\n";
                    out.write(headers.getBytes(StandardCharsets.US_ASCII));
                    out.write(body);
                    out.flush();
                    return body;
                } catch (IOException e) {
                    throw new RuntimeException("server failed: " + e.getMessage(), e);
                }
            });

            byte[] echoed;
            byte[] roundTripBody;
            try (Socket conn = new Socket()) {
                conn.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                OutputStream cout = conn.getOutputStream();
                String reqHeaders =
                        "POST /interceptor HTTP/1.1\r\n" +
                        "Host: 127.0.0.1\r\n" +
                        "Content-Type: application/javascript; charset=utf-8\r\n" +
                        "Content-Length: " + scriptBytes.length + "\r\n" +
                        "\r\n";
                cout.write(reqHeaders.getBytes(StandardCharsets.US_ASCII));
                cout.write(scriptBytes);
                cout.flush();

                // Read the echo response. If the server's body read had
                // hung, this would block indefinitely; we cap with a
                // deadline below to surface that as a test failure
                // rather than a CI hang.
                InputStream cin = conn.getInputStream();
                ByteArrayOutputStream resp = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = cin.read(buf)) != -1) {
                    resp.write(buf, 0, n);
                }
                byte[] full = resp.toByteArray();

                // Skip past the response headers (CRLF CRLF) and pull
                // out the body the server echoed.
                int sep = indexOf(full, new byte[]{'\r', '\n', '\r', '\n'});
                assertTrue("expected response headers terminator", sep > 0);
                roundTripBody = new byte[full.length - sep - 4];
                System.arraycopy(full, sep + 4, roundTripBody, 0, roundTripBody.length);

                try {
                    echoed = serverEchoed.get(2, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    throw new AssertionError(
                        "server hung on multi-byte UTF-8 body — regression of the " +
                        "char-based BufferedReader bug. Expected to read " +
                        scriptBytes.length + " bytes within 2s.");
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                }
            }

            assertArrayEquals("server received the exact bytes the client sent",
                    scriptBytes, echoed);
            assertArrayEquals("client got the exact body back from the server",
                    scriptBytes, roundTripBody);
            assertEquals("UTF-8 round trip preserves the original string",
                    script, new String(roundTripBody, StandardCharsets.UTF_8));
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
