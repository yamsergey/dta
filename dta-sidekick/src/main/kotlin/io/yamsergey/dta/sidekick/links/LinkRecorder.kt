package io.yamsergey.dta.sidekick.links

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import io.yamsergey.dta.protocol.network.Header
import io.yamsergey.dta.protocol.network.LinkDelivery
import io.yamsergey.dta.protocol.network.LinkEntry
import io.yamsergey.dta.protocol.rpc.CapabilityStatus
import io.yamsergey.dta.sidekick.network.NetworkRuntime
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * Records every link that reaches an activity of the app (feature 012, research R2) with
 * observe-only means: activity lifecycle callbacks, and the new-intent listener a
 * `ComponentActivity` offers (added by reflection: the sidekick does not depend on AndroidX).
 * Extras are read from a copy of the intent; the app's own is never touched (constitution I).
 */
object LinkRecorder {
    private val classifier = LinkClassifier()
    /** Activities whose new intents only show when the app calls setIntent (no listener API). */
    private val unobservable = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var started = false

    @Synchronized
    fun start(app: Application) {
        if (started) return
        started = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = guard {
                listenForNewIntents(activity)
                classifier.created(activity, savedInstanceState != null, view(activity.intent))?.let { record(activity, activity.intent, it) }
            }
            override fun onActivityResumed(activity: Activity) = guard {
                classifier.resumed(activity, view(activity.intent))?.let { record(activity, activity.intent, it) }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** The links capability: what is recorded, and which activities' new intents cannot be seen. */
    fun capability(): CapabilityStatus = if (unobservable.isEmpty()) {
        CapabilityStatus(NAME, CapabilityStatus.Status.AVAILABLE)
    } else {
        CapabilityStatus(
            NAME, CapabilityStatus.Status.DEGRADED,
            "new intents of ${unobservable.sorted().joinToString(", ")} are seen only when the activity calls setIntent (not a ComponentActivity)",
        )
    }

    private fun listenForNewIntents(activity: Activity) {
        try {
            val consumer = Class.forName("androidx.core.util.Consumer", false, activity.javaClass.classLoader)
            val add = activity.javaClass.getMethod("addOnNewIntentListener", consumer)
            val listener = Proxy.newProxyInstance(consumer.classLoader, arrayOf(consumer)) { proxy, method, args ->
                when (method.name) {
                    "accept" -> {
                        guard {
                            val intent = args?.firstOrNull() as? Intent ?: return@guard
                            classifier.newIntent(activity, LinkClassifier.IntentView(intent.dataString, intent.flags, intent))?.let { record(activity, intent, it) }
                        }
                        null
                    }
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "DTA link listener"
                    else -> null
                }
            }
            add.invoke(activity, listener)
        } catch (_: Throwable) {
            unobservable += activity.javaClass.name
        }
    }

    private fun view(intent: Intent?): LinkClassifier.IntentView? = intent?.let { LinkClassifier.IntentView(it.dataString, it.flags, it) }

    private fun record(activity: Activity, intent: Intent, delivery: LinkDelivery) {
        val copy = Intent(intent)
        val data: Uri? = copy.data
        val referrer = if (Build.VERSION.SDK_INT >= 22) runCatching { activity.referrer?.toString() }.getOrNull() else null
        NetworkRuntime.recorder?.link { id, runId, time ->
            LinkEntry(
                id = id, runId = runId, timeMs = time,
                uri = copy.dataString ?: "",
                scheme = data?.scheme,
                host = data?.host,
                path = data?.path,
                query = runCatching { data?.queryParameterNames?.flatMap { name -> data.getQueryParameters(name).map { Header(name, it) } } }.getOrNull().orEmpty(),
                action = copy.action,
                categories = copy.categories?.sorted().orEmpty(),
                flags = "0x" + Integer.toHexString(copy.flags),
                extras = extras(copy, activity.classLoader),
                activity = activity.javaClass.name,
                delivery = delivery,
                referrer = referrer,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun extras(copy: Intent, loader: ClassLoader) = try {
        val bundle = copy.extras ?: Bundle.EMPTY
        bundle.classLoader = loader
        Extras.render(bundle.keySet().sorted(), { key -> bundle.get(key) }) { value ->
            (value as? Bundle)?.let { b -> b.keySet().associateWith { k -> runCatching { b.get(k) }.getOrElse { "not read: ${it.message}" } } }
        }
    } catch (t: Throwable) {
        listOf(io.yamsergey.dta.protocol.network.IntentExtra("*", notRead = "the extras could not be read: ${t.message ?: t.javaClass.simpleName}"))
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Recording never disturbs the app (constitution I).
        }
    }

    const val NAME = "links"
}
