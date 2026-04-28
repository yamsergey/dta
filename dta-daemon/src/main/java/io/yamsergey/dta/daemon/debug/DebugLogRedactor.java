package io.yamsergey.dta.daemon.debug;

import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts likely-sensitive data from text content before it ships in the
 * debug bundle.
 *
 * <p>The goal is "safe to attach to a public GitHub issue" — not full
 * scrubbing. Anyone determined enough can reverse-engineer some of this
 * (line counts, message shapes, etc.). What's covered:
 * <ul>
 *   <li>The host package name → stable {@code app_<8hex>} hash. Stable
 *       within a bundle so reading the redacted log still lets you trace
 *       call flow ("the same app is doing X then Y") without leaking the
 *       app's identity.</li>
 *   <li>{@code Authorization} / {@code Cookie} / {@code Set-Cookie} /
 *       {@code X-Api-Key} header values → {@code [REDACTED]}. Headers are
 *       case-insensitive per HTTP, so the regex is too.</li>
 *   <li>JWT tokens — the {@code eyJ...\.eyJ...\.....} pattern is
 *       distinctive enough that it can appear unfielded in log lines
 *       (e.g. inside an exception stack trace) and we still want to
 *       catch it.</li>
 *   <li>Email addresses — common case is the user's login email
 *       appearing in OAuth state parameters or logout calls.</li>
 * </ul></p>
 *
 * <p>What's NOT covered (and the reasoning):
 * <ul>
 *   <li>URLs in general — too many false positives. Public REST endpoints
 *       are probably fine; if a URL contains credentials in the query,
 *       the rules above usually catch them via a header echo elsewhere.</li>
 *   <li>IP addresses — internal-LAN ranges are operationally useful;
 *       public IPs are user/server identifiable but rarely leak more than
 *       reverse-DNS could.</li>
 *   <li>Long opaque strings — base64/hex sequences are too easy to
 *       confuse with hashes, request IDs, etc.</li>
 * </ul>
 * If a user needs more aggressive redaction they can disable file logging
 * entirely; this redactor sits in the "good enough for issue triage"
 * range.</p>
 */
public class DebugLogRedactor {

    /** Header value matcher: {@code Header: <value>}, case-insensitive on the name. */
    private static final Pattern HEADER_VALUE = Pattern.compile(
            "(?im)^(\\s*(?:Authorization|Cookie|Set-Cookie|X-Api-Key|Proxy-Authorization)\\s*:\\s*).+$");

    /** Header value matcher when the header is inline (e.g. JSON-quoted). */
    private static final Pattern HEADER_VALUE_QUOTED = Pattern.compile(
            "(?i)\"(Authorization|Cookie|Set-Cookie|X-Api-Key|Proxy-Authorization)\"\\s*:\\s*\"[^\"]+\"");

    /** JWT — three base64url segments separated by dots, leading {@code eyJ}. */
    private static final Pattern JWT = Pattern.compile(
            "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    /** Email — pragmatic, not RFC-strict. */
    private static final Pattern EMAIL = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    private final Pattern packageNamePattern;
    private final String packageReplacement;

    public DebugLogRedactor(String packageName) {
        // Hash the package once — same input → same replacement, so a
        // redacted bundle stays internally cross-referenceable.
        this.packageReplacement = "app_" + shortHash(packageName);
        // Pattern.quote because package names contain '.' which would
        // otherwise be regex any-char.
        this.packageNamePattern = Pattern.compile(Pattern.quote(packageName));
    }

    public String redact(String input) {
        if (input == null || input.isEmpty()) return input;
        String s = input;
        // Order matters slightly: hash the package first so its dots
        // don't accidentally match the JWT pattern. JWTs and emails are
        // unambiguous so they can run in any order.
        s = packageNamePattern.matcher(s).replaceAll(Matcher.quoteReplacement(packageReplacement));
        s = HEADER_VALUE.matcher(s).replaceAll("$1[REDACTED]");
        s = HEADER_VALUE_QUOTED.matcher(s).replaceAll("\"$1\":\"[REDACTED]\"");
        s = JWT.matcher(s).replaceAll("[JWT_REDACTED]");
        s = EMAIL.matcher(s).replaceAll("[EMAIL_REDACTED]");
        return s;
    }

    /** First 8 hex chars of SHA-256(input). Stable, unambiguous, short. */
    private static String shortHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "00000000";
        }
    }
}
