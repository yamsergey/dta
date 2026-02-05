package io.yamsergey.dta.sidekick.customtabs;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.network.adapter.AdapterType;
import io.yamsergey.dta.sidekick.network.adapter.NetworkAdapter;

/**
 * Network adapter for intercepting Chrome Custom Tabs launches.
 *
 * <p>This adapter hooks into the AndroidX Browser library's CustomTabsIntent
 * class to detect when the app opens URLs in Chrome Custom Tabs.</p>
 *
 * <p>Unlike HTTP adapters which capture actual request/response data,
 * this adapter captures the intent to open a URL. External tools can use
 * this information to connect to Chrome's DevTools Protocol and capture
 * the actual network traffic.</p>
 */
public class CustomTabsAdapter implements NetworkAdapter {

    @Override
    public String getId() {
        return "customtabs";
    }

    @Override
    public String getName() {
        return "Chrome Custom Tabs";
    }

    @Override
    public AdapterType getType() {
        return AdapterType.CUSTOM_TABS;
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("androidx.browser.customtabs.CustomTabsIntent");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public List<MethodHook> getHooks() {
        if (!isAvailable()) {
            return Collections.emptyList();
        }
        return Arrays.asList(
            new CustomTabsLaunchHook()
        );
    }

    @Override
    public int getPriority() {
        // Lower priority - not critical for core functionality
        return -10;
    }

    @Override
    public boolean isEnabledByDefault() {
        // Enabled by default since it's lightweight
        return true;
    }
}
