package io.yamsergey.dta.sidekick

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import io.yamsergey.dta.protocol.rpc.AppInstallation
import java.security.MessageDigest

/**
 * The installed build of the app, read inside it (feature 011, research R5): the host tells an
 * update or a reinstall from a cleared history by it. Read on request; any failure gives null,
 * which the host states as an unknown installation.
 */
internal object AppInstallations {
    fun of(context: Context): AppInstallation? = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        AppInstallation(
            versionName = info.versionName,
            versionCode = versionCode(info),
            firstInstallMs = info.firstInstallTime,
            lastUpdateMs = info.lastUpdateTime,
            signingSha256 = signer(info)?.let(::sha256),
        )
    } catch (_: Throwable) {
        null
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

    private fun signer(info: PackageInfo): ByteArray? =
        if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
