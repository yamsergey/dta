package io.yamsergey.dta.daemon

import io.yamsergey.dta.daemon.adb.AdbExec
import io.yamsergey.dta.protocol.layout.SelectedElement
import io.yamsergey.dta.protocol.layout.Selections
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** D-134 (055 FR-007): the selection is cleared and the clearing stated when the app process is gone, with or without a watcher running. */
class LayoutSelectionEndsTest {
    private fun daemon(pidof: AdbExec.Result) = DtaDaemon(
        runAdb = { args -> if (args.contains("pidof")) pidof else AdbExec.Result(0, "", "") },
        adbPath = "/fake/adb",
        dataDir = Files.createTempDirectory("dta").toFile(),
    )

    private fun DtaDaemon.selected() = runBlocking {
        layoutSelect("d", "a", "set", elements = listOf(SelectedElement(JsonObject(emptyMap()), listOf("Button"), 100)))
    }

    @Test
    fun `a read after the process is gone is empty and says why`() = runBlocking {
        val d = daemon(AdbExec.Result(1, "", ""))
        d.selected()
        val s = d.layoutSelection("d", "a")
        assertEquals(emptyList(), s.elements)
        assertEquals(Selections.ENDED, s.note)
    }

    @Test
    fun `a running process keeps the selection`() = runBlocking {
        val d = daemon(AdbExec.Result(0, "4242\n", ""))
        d.selected()
        assertEquals(1, d.layoutSelection("d", "a").elements.size)
    }

    @Test
    fun `an adb failure is not the app ending`() = runBlocking {
        val d = daemon(AdbExec.Result(1, "", "error: device offline"))
        d.selected()
        assertEquals(1, d.layoutSelection("d", "a").elements.size)
    }
}
