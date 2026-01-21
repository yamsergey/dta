package io.yamsergey.dta.sidekick.network.adapter.http;

import java.util.Arrays;
import java.util.List;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.network.adapter.AdapterType;
import io.yamsergey.dta.sidekick.network.adapter.NetworkAdapter;

/**
 * Network adapter for Java's HttpURLConnection.
 *
 * <p>Intercepts HTTP traffic made via {@code java.net.HttpURLConnection} and its
 * Android implementation {@code com.android.okhttp.internal.huc.HttpURLConnectionImpl}.</p>
 *
 * <p>Hook points:</p>
 * <ul>
 *   <li>{@code getInputStream()} - captures response when reading</li>
 *   <li>{@code getOutputStream()} - captures request body stream</li>
 *   <li>{@code connect()} - captures connection start</li>
 * </ul>
 */
public class UrlConnectionAdapter implements NetworkAdapter {

    // Android's internal HttpURLConnection implementation
    private static final String ANDROID_HTTP_URL_CONNECTION = "com.android.okhttp.internal.huc.HttpURLConnectionImpl";
    // Standard Java HttpURLConnection (abstract)
    private static final String JAVA_HTTP_URL_CONNECTION = "java.net.HttpURLConnection";

    @Override
    public String getId() {
        return "urlconnection";
    }

    @Override
    public String getName() {
        return "HttpURLConnection";
    }

    @Override
    public AdapterType getType() {
        return AdapterType.HTTP;
    }

    @Override
    public boolean isAvailable() {
        // HttpURLConnection is always available on Android
        try {
            Class.forName(JAVA_HTTP_URL_CONNECTION);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public List<MethodHook> getHooks() {
        return Arrays.asList(
            new UrlConnectionGetInputStreamHook()
        );
    }

    @Override
    public int getPriority() {
        // Lower priority than OkHttp - URLConnection is less common
        return 50;
    }
}
