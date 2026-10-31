package io.yamsergey.dta.sidekick.links

import io.yamsergey.dta.protocol.network.LinkDelivery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 012 T003: which activity events are a link that reached the app, and how. */
class LinkClassifierTest {
    private val c = LinkClassifier()
    private val main = Any()
    private val deep = Any()
    private fun intent(data: String?, flags: Int = 0) = LinkClassifier.IntentView(data, flags, Any())

    @Test
    fun `the first activity of the process is a cold start, later ones are created`() {
        assertEquals(LinkDelivery.COLD_START, c.created(deep, savedState = false, intent("fixture://item/1")))
        assertEquals(LinkDelivery.CREATED, c.created(Any(), savedState = false, intent("fixture://item/2")))
    }

    @Test
    fun `a re-created activity and a launcher start are no links`() {
        assertNull(c.created(main, savedState = false, intent(null)), "launcher: no URI")
        assertNull(c.created(deep, savedState = true, intent("fixture://item/1")), "rotation or restore: the same intent again")
    }

    @Test
    fun `a task brought back from recents is marked so`() {
        assertEquals(LinkDelivery.FROM_RECENTS, c.created(deep, savedState = false, intent("fixture://item/1", flags = LinkClassifier.FLAG_LAUNCHED_FROM_HISTORY)))
    }

    @Test
    fun `a new intent is a link once, through the listener or a changed getIntent`() {
        val first = intent("fixture://item/1")
        c.created(deep, savedState = false, first)
        val second = intent("fixture://item/2")
        assertEquals(LinkDelivery.NEW_INTENT, c.newIntent(deep, second))
        assertNull(c.resumed(deep, second), "the app's setIntent of the same intent is not a second link")
        val third = intent("fixture://item/3")
        assertEquals(LinkDelivery.NEW_INTENT, c.resumed(deep, third), "seen only through getIntent")
        assertNull(c.resumed(deep, third))
        assertNull(c.newIntent(deep, intent(null)), "a new intent without a URI")
    }
}
