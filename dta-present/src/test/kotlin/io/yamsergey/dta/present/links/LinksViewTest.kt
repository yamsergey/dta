package io.yamsergey.dta.present.links

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 012 T012: what the Links view shows. */
class LinksViewTest {
    private val link = """{"kind":"link","id":"r1-1:3","runId":"r1-1","timeMs":3600000,"uri":"fixture://item/42","activity":"io.example.DeepLinkActivity","delivery":"new-intent",
        "extras":[{"key":"blob","notRead":"class not found"}],"query":[{"name":"ref","value":"mail"}]}"""

    @Test
    fun `rows and detail`() {
        val page = Json.parseToJsonElement("""{"entries":[$link],"total":1,"coverage":{"searched":1,"stored":1,"persisted":true}}""").jsonObject
        val row = LinksView.rows(page, ZoneOffset.UTC).single()
        assertEquals(LinkRow("r1-1:3", "01:00:00", "fixture://item/42", "new intent", "DeepLinkActivity"), row)
        val detail = LinksView.detail(Json.parseToJsonElement(link).jsonObject)
        assertTrue(detail.contains("Referrer" to "unknown"), "$detail")
        assertTrue(detail.contains("ref" to "mail"), "$detail")
        assertTrue(detail.contains("blob" to "not read: class not found"), "$detail")
        assertEquals(LinksView.EMPTY, "No links have reached the app yet. Open one with Open Link…, or from another app.")
    }

    @Test
    fun `an opened link as one sentence, and verification rows`() {
        assertEquals("Opened by com.android.chrome. links.dta.invalid: not verified — code 1024.",
            LinksView.opened(Json.parseToJsonElement("""{"outcome":"other-app","packageName":"com.android.chrome","appLinks":{"host":"links.dta.invalid","state":"not verified","reason":"code 1024"}}""").jsonObject))
        val rows = LinksView.verification(Json.parseToJsonElement("""{"available":true,"hosts":[{"host":"a.example","state":"verified","enabled":false}]}""").jsonObject)
        assertEquals(listOf("a.example: verified · turned off by the user"), rows)
        assertEquals(listOf("needs Android 12"), LinksView.verification(Json.parseToJsonElement("""{"available":false,"reason":"needs Android 12"}""").jsonObject))
    }
}
