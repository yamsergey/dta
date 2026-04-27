package io.yamsergey.dta.sidekick.init;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

/**
 * High-priority ContentProvider whose only job is to install the
 * {@code dta-sidekick-shim} jar onto the Android bootstrap classloader
 * <i>before</i> AndroidX Startup runs.
 *
 * <p><b>Why a separate provider, why this early:</b> AndroidX Startup's
 * {@code InitializationProvider} eagerly {@code Class.forName}'s
 * {@code SidekickInitializer}, which links its transitive types — including
 * {@code MethodHook} (referenced by the hook classes it instantiates).
 * Without the shim on bootstrap by then, that link fails with
 * {@link NoClassDefFoundError} and the host app crashes on launch.</p>
 *
 * <p>Manifest declares this provider with {@code android:initOrder} higher
 * than {@code androidx.startup.InitializationProvider} so Android attaches
 * us first.</p>
 *
 * <p><b>Why a ContentProvider:</b> ContentProviders'
 * {@code attachInfo}/{@code onCreate} run before {@code Application.onCreate}
 * and are the earliest hook point inside an Android process that doesn't
 * require host-app cooperation. Same pattern AndroidX Startup itself uses.</p>
 *
 * <p><b>What we deliberately do NOT reference:</b> any class that
 * transitively touches {@code MethodHook} / {@code HookRegistry}. This
 * provider's class link must succeed without the shim — that's what we're
 * here to install. Stick to {@code java.*} + {@code android.util.Log} and
 * we're safe.</p>
 *
 * <p>Failures here are non-fatal: we log and let the host app continue.
 * The host app loses boot-class hook capability but app-class hooks still
 * work.</p>
 */
public final class BootstrapShimProvider extends ContentProvider {

    private static final String TAG = "DtaBootShim";
    private static final String SHIM_ASSET = "dta-shim.jar";

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            Log.w(TAG, "ContentProvider has no context — skipping shim install");
            return false;
        }
        // Delegate to BootstrapShim — that class is intentionally
        // dispatcher-free so its class link cannot trigger NCDFE. This
        // ContentProvider exists purely to give us an attach point that
        // runs before AndroidX Startup.
        BootstrapShim.install(context);
        return true;
    }

    // ContentProvider API surface — we don't actually expose any data,
    // we just abuse the lifecycle hook.

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) { return null; }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
