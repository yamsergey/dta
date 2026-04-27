package io.yamsergey.dta.sidekick.chromeintent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.yamsergey.dta.sidekick.jvmti.MethodHook;
import io.yamsergey.dta.sidekick.network.adapter.AdapterType;
import io.yamsergey.dta.sidekick.network.adapter.NetworkAdapter;

/**
 * Network adapter for intercepting standalone Chrome launches via
 * {@code Intent.ACTION_VIEW} (i.e. not Custom Tabs).
 *
 * <p>Hooks {@code android.app.Activity.startActivityForResult(Intent, int, Bundle)}
 * — the funnel point that every {@code startActivity*} overload delegates to.
 * That makes this a boot-class hook, which requires the bootstrap shim
 * (installed by {@code BootstrapShimProvider} before AndroidX Startup runs).</p>
 *
 * <p>Previous incarnation hooked {@code androidx.activity.ComponentActivity.startActivityForResult}
 * to avoid the boot-class requirement. That left a coverage gap for
 * Activity subclasses that don't extend ComponentActivity — Auth0's
 * {@code AuthenticationActivity} is the canonical case. The new hook
 * closes that gap.</p>
 *
 * <p>Always available — {@code android.app.Activity} is part of the
 * Android framework, no classpath check needed.</p>
 */
public class ChromeIntentAdapter implements NetworkAdapter {

    @Override
    public String getId() {
        return "chromeintent";
    }

    @Override
    public String getName() {
        return "Chrome via Intent";
    }

    @Override
    public AdapterType getType() {
        return AdapterType.CUSTOM_TABS;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public List<MethodHook> getHooks() {
        return Collections.singletonList(new ActivityStartActivityForResultHook());
    }

    @Override
    public int getPriority() {
        return -10;
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }
}
