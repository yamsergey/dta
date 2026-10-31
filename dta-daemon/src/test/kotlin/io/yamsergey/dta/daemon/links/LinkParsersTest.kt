package io.yamsergey.dta.daemon.links

import io.yamsergey.dta.protocol.network.LinkOpenOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 012 T008/T010: what the device's activity and package managers said. */
class LinkParsersTest {
    private val pkg = "io.yamsergey.dta.fixture.channel"

    @Test
    fun `am start - the app, another app, a chooser, nothing`() {
        val own = LinkParsers.launch("Starting: Intent { act=android.intent.action.VIEW dat=fixture://item/... }\nStatus: ok\nLaunchState: COLD\nActivity: $pkg/.DeepLinkActivity\nTotalTime: 300\nComplete\n", pkg)
        assertEquals(LinkOpenOutcome.RECEIVED, own.outcome)
        assertEquals("$pkg/.DeepLinkActivity", own.activity)

        val browser = LinkParsers.launch("Status: ok\nActivity: com.android.chrome/com.google.android.apps.chrome.Main\nComplete\n", pkg)
        assertEquals(LinkOpenOutcome.OTHER_APP, browser.outcome)
        assertEquals("com.android.chrome", browser.packageName)

        val chooser = LinkParsers.launch("Status: ok\nActivity: android/com.android.internal.app.ResolverActivity\nComplete\n", pkg)
        assertEquals(LinkOpenOutcome.CHOOSER, chooser.outcome)
        assertEquals(LinkOpenOutcome.CHOOSER, LinkParsers.launch("Status: ok\nActivity: com.android.intentresolver/.ChooserActivity\n", pkg).outcome)

        val none = LinkParsers.launch("Starting: Intent { act=android.intent.action.VIEW dat=nope://x }\nError: Activity not started, unable to resolve Intent { act=android.intent.action.VIEW dat=nope://x flg=0x10000000 }\n", pkg)
        assertEquals(LinkOpenOutcome.NOT_HANDLED, none.outcome)
        assertTrue("unable to resolve" in none.reason!!)
    }

    @Test
    fun `delivered to a running activity of the app is received as a new intent`() {
        val top = LinkParsers.launch("Starting: Intent { act=android.intent.action.VIEW dat=fixture://item/... pkg=$pkg }\nWarning: Activity not started, intent has been delivered to currently running top-most instance.\nStatus: ok\nLaunchState: UNKNOWN (0)\nActivity: $pkg/.DeepLinkActivity\nComplete\n", pkg)
        assertEquals(LinkOpenOutcome.RECEIVED, top.outcome)
        assertTrue(top.reason!!.contains("running"), top.reason)
    }

    @Test
    fun `a URI that could break out of the shell is refused`() {
        assertFailsWith<IllegalArgumentException> { LinkParsers.shellQuote("fixture://x'; rm -rf /") }
        assertEquals("'fixture://item/42?a=1&b=2'", LinkParsers.shellQuote("fixture://item/42?a=1&b=2"))
    }

    @Test
    fun `app links - per host, the user's choice, and unavailable before Android 12`() {
        val text = """
              $pkg:
                ID: 0b5f3c3a-1111-2222-3333-444455556666
                Signatures: [AB:CD]
                Domain verification state:
                  shop.example.com: verified
                  links.dta.invalid: 1024
                User 0:
                  Verification link handling allowed: true
                  Selection state:
                    Disabled:
                      links.dta.invalid
        """.trimIndent()
        val state = LinkParsers.appLinks(text, sdk = 35)
        assertTrue(state.available)
        assertEquals(true, state.linkHandlingAllowed)
        val byHost = state.hosts.associateBy { it.host }
        assertEquals("verified", byHost.getValue("shop.example.com").state)
        assertEquals(null, byHost.getValue("shop.example.com").reason)
        assertEquals("not verified", byHost.getValue("links.dta.invalid").state)
        assertTrue(byHost.getValue("links.dta.invalid").reason!!.contains("1024"))
        assertEquals(false, byHost.getValue("links.dta.invalid").enabled)

        val old = LinkParsers.appLinks("", sdk = 30)
        assertTrue(!old.available && old.reason!!.contains("Android 12"))
        val none = LinkParsers.appLinks("  $pkg:\n    ID: x\n    Signatures: [AB]\n", sdk = 35)
        assertTrue(none.hosts.isEmpty())
        assertTrue(none.reason!!.contains("no App Links"))
        assertNull(LinkParsers.appLinks(text, sdk = 35).reason)
    }
}
