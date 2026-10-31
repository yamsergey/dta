package io.yamsergey.dta.daemon.links

import io.yamsergey.dta.protocol.network.AppLinkHost
import io.yamsergey.dta.protocol.network.AppLinksState
import io.yamsergey.dta.protocol.network.LinkOpenOutcome
import io.yamsergey.dta.protocol.network.LinkOpenResult

/** The device's own words about links (feature 012, research R3/R4), read into results. */
object LinkParsers {
    private val RESOLVERS = listOf("com.android.internal.app.ResolverActivity", "com.android.internal.app.ChooserActivity", "com.android.intentresolver")

    /** `am start -W -a VIEW -d <uri>`: who received it, for the app [pkg]. */
    fun launch(output: String, pkg: String?): LinkOpenResult {
        val lines = output.lines().map { it.trim() }
        lines.firstOrNull { it.startsWith("Error:") }?.let { return LinkOpenResult(LinkOpenOutcome.NOT_HANDLED, reason = it.removePrefix("Error:").trim()) }
        val toTop = lines.firstOrNull { it.startsWith("Warning:") && "delivered to currently running" in it }
        val activity = lines.firstOrNull { it.startsWith("Activity:") }?.removePrefix("Activity:")?.trim()
        val owner = activity?.substringBefore('/')
        return when {
            activity != null && RESOLVERS.any { it in activity } ->
                LinkOpenResult(LinkOpenOutcome.CHOOSER, activity, reason = "several apps can open it and none is the default; the system asks which")
            owner != null && owner == pkg ->
                LinkOpenResult(LinkOpenOutcome.RECEIVED, activity, owner, reason = toTop?.let { "delivered to the running activity as a new intent" })
            owner != null -> LinkOpenResult(LinkOpenOutcome.OTHER_APP, activity, owner)
            toTop != null && pkg != null -> LinkOpenResult(LinkOpenOutcome.RECEIVED, packageName = pkg, reason = "delivered to the running activity as a new intent")
            else -> LinkOpenResult(LinkOpenOutcome.NOT_HANDLED, reason = lines.lastOrNull { it.isNotEmpty() } ?: "no answer from the activity manager")
        }
    }

    /** A URI as one shell word; a quote could end the word, so it is refused. */
    fun shellQuote(uri: String): String {
        require('\'' !in uri && '\n' !in uri) { "the URI contains a quote or a line break; DTA does not pass it to the device shell" }
        return "'$uri'"
    }

    /** `pm get-app-links --user current <pkg>` (Android 12+), per host with the user's selection. */
    fun appLinks(output: String, sdk: Int): AppLinksState {
        if (sdk < 31) return AppLinksState(available = false, reason = "App Links verification state needs Android 12 or newer; this device is API $sdk")
        val hosts = LinkedHashMap<String, AppLinkHost>()
        var section = ""
        var selection = ""
        var allowed: Boolean? = null
        for (raw in output.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("Domain verification state:") -> section = "verification"
                line.startsWith("User ") && line.endsWith(":") -> section = "user"
                line.startsWith("Verification link handling allowed:") -> allowed = line.substringAfter(':').trim().toBooleanStrictOrNull()
                line.startsWith("Selection state:") -> section = "selection"
                section == "selection" && (line == "Enabled:" || line == "Disabled:" || line == "None:") -> selection = line.removeSuffix(":")
                section == "verification" && ':' in line -> {
                    val host = line.substringBefore(':').trim()
                    val value = line.substringAfter(':').trim()
                    hosts[host] = when (value) {
                        "verified" -> AppLinkHost(host, "verified")
                        "approved" -> AppLinkHost(host, "approved", "approved by the user or a system app")
                        "denied" -> AppLinkHost(host, "denied", "denied by the system")
                        "none" -> AppLinkHost(host, "not verified", "no verification has been attempted")
                        "legacy_failure" -> AppLinkHost(host, "not verified", "verification failed (legacy)")
                        "migrated", "restored" -> AppLinkHost(host, value, "carried over from an earlier install")
                        else -> AppLinkHost(host, "not verified", "verification failed or is pending (code $value): the site's assetlinks.json did not confirm this app")
                    }
                }
                section == "selection" && selection.isNotEmpty() && line.isNotEmpty() && ':' !in line -> {
                    hosts[line]?.let { hosts[line] = it.copy(enabled = selection == "Enabled") }
                }
            }
        }
        return AppLinksState(
            available = true,
            hosts = hosts.values.toList(),
            linkHandlingAllowed = allowed,
            reason = if (hosts.isEmpty()) "the app declares no App Links hosts (no https intent filter with autoVerify)" else null,
        )
    }
}
