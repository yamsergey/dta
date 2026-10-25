package io.yamsergey.dta.sidekick.hooks

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import io.yamsergey.dta.sidekick.network.NetworkRuntime

/**
 * Attaches the hook agent as early as a library can run: a ContentProvider whose
 * initOrder places it before AndroidX Startup (and so before the app's own initializers
 * and Application.onCreate). Network capture is observation, which constitution v1.3.0
 * allows without a connected client. Never throws into the app.
 */
class HooksProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val context = context ?: return false
        try {
            HookAttach.attach(context)
            NetworkRuntime.start(context)
        } catch (t: Throwable) {
            Log.w("DtaHooks", "hook attach crashed: $t")
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
