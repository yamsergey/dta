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
 * <p>Hooks {@code androidx.activity.ComponentActivity.startActivityForResult}
 * (both 2-arg and 3-arg overloads). Activity.startActivity delegates to
 * startActivityForResult via virtual dispatch, so hooking the override on
 * ComponentActivity catches every startActivity call from a
 * ComponentActivity-extending Activity.</p>
 *
 * <p>Available only when AndroidX Activity is on the classpath. Apps that
 * don't use AndroidX (rare today) are not covered by this adapter.</p>
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
        try {
            Class.forName("androidx.activity.ComponentActivity");
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
            new ChromeIntentLaunchHook(),                             // (Intent, int)
            new ChromeIntentLaunchHook("(Landroid/content/Intent;ILandroid/os/Bundle;)V")
        );
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
