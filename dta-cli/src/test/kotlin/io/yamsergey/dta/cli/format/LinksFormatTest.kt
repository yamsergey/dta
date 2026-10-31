package io.yamsergey.dta.cli.format

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 012 T006/T009/T010: links in the terminal. */
class LinksFormatTest {
    private val f = Formatters(Style.PLAIN)
    private val link = """{"kind":"link","id":"r1-1:3","runId":"r1-1","timeMs":0,"uri":"fixture://item/42?ref=mail","scheme":"fixture","host":"item","path":"/42",
        "query":[{"name":"ref","value":"mail"}],"action":"android.intent.action.VIEW","categories":["android.intent.category.BROWSABLE"],"flags":"0x10000000",
        "extras":[{"key":"token","type":"String","value":"abc"},{"key":"blob","notRead":"class not found"}],
        "activity":"io.example.DeepLinkActivity","delivery":"cold-start","referrer":"android-app://com.android.shell"}"""

    @Test
    fun `a list row names the URI, how it arrived and where`() {
        val text = f.format("links.list", Json.parseToJsonElement("""{"entries":[$link],"total":1,"coverage":{"searched":1,"stored":1,"persisted":true}}"""))
        assertTrue("r1-1:3" in text && "fixture://item/42?ref=mail" in text && "cold-start" in text && "DeepLinkActivity" in text, text)
        assertEquals("No links have reached the app.", f.format("links.list", Json.parseToJsonElement("""{"entries":[],"total":0,"coverage":{"searched":0,"stored":0,"persisted":true}}""")).lines().first())
    }

    @Test
    fun `an entry shows every part, an unread extra with why`() {
        val text = f.format("links.entry", Json.parseToJsonElement("""{"entry":$link}"""))
        assertTrue("referrer    android-app://com.android.shell" in text, text)
        assertTrue("ref = mail" in text, text)
        assertTrue("token (String) = abc" in text, text)
        assertTrue("blob: ! not read: class not found" in text, text)
    }

    @Test
    fun `an opened link says who received it`() {
        assertEquals("Received by io.example/.DeepLinkActivity — link r1-1:9.", f.format("links.open", Json.parseToJsonElement("""{"outcome":"received","activity":"io.example/.DeepLinkActivity","packageName":"io.example","recordId":"r1-1:9"}""")))
        val browser = f.format("links.open", Json.parseToJsonElement("""{"outcome":"other-app","activity":"com.android.chrome/.Main","packageName":"com.android.chrome","appLinks":{"host":"shop.example.com","state":"not verified","reason":"code 1024"}}"""))
        assertTrue("Opened by another app: com.android.chrome" in browser && "shop.example.com: not verified (code 1024)" in browser, browser)
        assertTrue("Nothing on the device can open it" in f.format("links.open", Json.parseToJsonElement("""{"outcome":"not-handled","reason":"unable to resolve Intent"}""")))
    }

    @Test
    fun `verification per host`() {
        val text = f.format("links.verification", Json.parseToJsonElement("""{"available":true,"linkHandlingAllowed":true,"hosts":[{"host":"shop.example.com","state":"verified","enabled":true},{"host":"links.dta.invalid","state":"not verified","reason":"code 1024","enabled":false}]}"""))
        assertTrue("shop.example.com  verified" in text, text)
        assertTrue("links.dta.invalid  not verified — code 1024 · turned off by the user" in text, text)
        assertEquals("App Links verification state needs Android 12 or newer", f.format("links.verification", Json.parseToJsonElement("""{"available":false,"reason":"App Links verification state needs Android 12 or newer"}""")))
    }
}
