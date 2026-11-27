package io.yamsergey.dta.studio.data

/** D-122: a tab read once for an app run: [refresh] (tab shown, context changed, app ran again) loads it when it is shown and stale. */
class FreshTab(
    private val fresh: RunFreshness,
    private val shown: () -> Boolean,
    private val chosen: () -> Pair<String?, String?>,
    private val load: () -> Unit,
) {
    fun refresh() {
        if (!shown()) return
        val (device, app) = chosen()
        if (!fresh.stale(device, app)) return
        fresh.mark(device, app)
        load()
    }
}
