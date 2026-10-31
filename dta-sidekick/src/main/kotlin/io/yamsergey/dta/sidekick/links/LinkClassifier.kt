package io.yamsergey.dta.sidekick.links

import io.yamsergey.dta.protocol.network.LinkDelivery
import java.util.WeakHashMap

/**
 * Which activity events are a link that reached the app (feature 012, research R2): a new
 * activity with a URI (the process's first is a cold start; from recents is marked), and a new
 * intent with a URI — seen through the activity's new-intent listener or, without one, as a
 * changed `getIntent()` on resume. Re-creation with a saved state is the same intent again.
 */
class LinkClassifier {
    /** The intent's URI and flags; [identity] is the Intent object itself. */
    class IntentView(val data: String?, val flags: Int, val identity: Any)

    private var firstSeen = false
    private val last = WeakHashMap<Any, Any>()

    @Synchronized
    fun created(activity: Any, savedState: Boolean, intent: IntentView?): LinkDelivery? {
        val first = !firstSeen
        firstSeen = true
        intent?.let { last[activity] = it.identity }
        if (savedState || intent?.data == null) return null
        return when {
            intent.flags and FLAG_LAUNCHED_FROM_HISTORY != 0 -> LinkDelivery.FROM_RECENTS
            first -> LinkDelivery.COLD_START
            else -> LinkDelivery.CREATED
        }
    }

    @Synchronized
    fun newIntent(activity: Any, intent: IntentView): LinkDelivery? {
        last[activity] = intent.identity
        return if (intent.data != null) LinkDelivery.NEW_INTENT else null
    }

    @Synchronized
    fun resumed(activity: Any, intent: IntentView?): LinkDelivery? {
        if (intent == null || last[activity] === intent.identity) return null
        last[activity] = intent.identity
        return if (intent.data != null) LinkDelivery.NEW_INTENT else null
    }

    companion object {
        /** Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY. */
        const val FLAG_LAUNCHED_FROM_HISTORY = 0x00100000
    }
}
