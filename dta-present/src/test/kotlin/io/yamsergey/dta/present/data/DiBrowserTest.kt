package io.yamsergey.dta.present.data

import io.yamsergey.dta.protocol.data.DiBinding
import io.yamsergey.dta.protocol.data.DiGraph
import io.yamsergey.dta.protocol.data.DiReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiBrowserTest {
    // client ← api ← repo ← vm-param; db ← repo
    private val report = DiReport(listOf(
        DiGraph("metro", "AppGraph 1", listOf(
            DiBinding("io.ktor.client.HttpClient", "httpClient", "io.ktor.client.HttpClient", true, "singleton", id = "0.0"),
            DiBinding("app.WeatherApi", "weatherApi", "app.WeatherApi", true, "singleton", id = "0.1", dependsOn = listOf("0.0")),
            DiBinding("app.WeatherDb", "database", "app.db.WeatherDbImpl", true, "singleton", id = "0.2"),
            DiBinding("app.ForecastRepository", "forecastRepository", "app.ForecastRepository", true, "singleton", id = "0.3", dependsOn = listOf("0.1", "0.2")),
            DiBinding("app.Lazy", "lazyThing", null, false, "singleton", id = "0.4"),
        )),
        DiGraph("metro", "viewmodel ForecastViewModel 2 (constructor)", listOf(
            DiBinding("app.ForecastRepository", "repository", "app.ForecastRepository", true, "injected", id = "1.0", dependsOn = listOf("0.3")),
        )),
    ))
    private val di = DiBrowser(report)

    @Test
    fun `nodes read as their type's simple name, kind and instance, used-by is the reverse of depends-on`() {
        val repo = di.node("0.3")!!
        assertEquals("ForecastRepository", repo.title)
        assertEquals("singleton · created", repo.state)
        assertEquals(listOf("0.1", "0.2"), di.dependsOn("0.3").map { it.id })
        assertEquals(listOf("1.0"), di.usedBy("0.3").map { it.id })
        assertEquals("singleton · not created yet", di.node("0.4")!!.state)
        assertEquals("viewmodel ForecastViewModel 2 (constructor)", di.node("1.0")!!.graph)
    }

    @Test
    fun `the neighbourhood is columns — dependencies left, the node, dependents right, two levels each way`() {
        val n = di.neighbourhood("0.3")
        assertEquals(listOf(listOf("0.0"), listOf("0.1", "0.2"), listOf("0.3"), listOf("1.0")), n.columns)
        assertTrue(Pair("0.3", "0.1") in n.edges && Pair("1.0", "0.3") in n.edges)
        assertEquals(listOf(listOf("0.1"), listOf("0.0")), di.neighbourhood("0.0").columns.let { listOf(it[0], it[1]) }.reversed())
    }

    @Test
    fun `the whole graph is layered by dependency depth, leaves first`() {
        val w = di.whole()
        assertEquals(listOf("0.0", "0.2", "0.4"), w.columns[0])
        assertEquals(listOf("0.1"), w.columns[1])
        assertEquals(listOf("0.3"), w.columns[2])
        assertEquals(listOf("1.0"), w.columns[3])
        assertEquals(4, w.edges.size)
    }
}
