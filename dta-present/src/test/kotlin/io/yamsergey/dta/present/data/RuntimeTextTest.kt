package io.yamsergey.dta.present.data

import io.yamsergey.dta.protocol.data.ActivityInfo
import io.yamsergey.dta.protocol.data.MemoryState
import io.yamsergey.dta.protocol.data.NavDestinationInfo
import io.yamsergey.dta.protocol.data.NavEntryInfo
import io.yamsergey.dta.protocol.data.NavHostInfo
import io.yamsergey.dta.protocol.data.NavigationState
import io.yamsergey.dta.protocol.data.PropertyInfo
import io.yamsergey.dta.protocol.data.RuntimeActivities
import io.yamsergey.dta.protocol.data.ViewModelInfo
import io.yamsergey.dta.protocol.data.ViewModelList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeTextTest {
    @Test
    fun `activities with state and focus`() {
        assertEquals(
            "pid 4242 app.process\n  MainActivity  resumed  task 12  focused  (a1)",
            RuntimeText.activities(RuntimeActivities(4242, "app.process", listOf(ActivityInfo("a1", "app.MainActivity", "resumed", 12, focused = true)))),
        )
    }

    @Test
    fun `a back stack top last, then the graph`() {
        val nav = NavigationState(
            listOf(
                NavHostInfo(
                    "c1", "navigation",
                    listOf(NavEntryInfo(route = "catalog"), NavEntryInfo(route = "product/{id}", arguments = mapOf("id" to "42"))),
                    NavDestinationInfo(navigator = "navigation", children = listOf(NavDestinationInfo("catalog", 1, navigator = "composable", start = true), NavDestinationInfo("product/{id}", 2, navigator = "composable", arguments = listOf("id")))),
                ),
            ),
        )
        assertEquals(
            """
            navigation c1
              back stack (top last)
                catalog
                product/{id}  id=42
              graph
                catalog  composable  (start)
                product/{id}  composable  args: id
            """.trimIndent(),
            RuntimeText.navigation(nav),
        )
    }

    @Test
    fun `view models list their properties, editable ones marked`() {
        val list = ViewModelList(
            listOf(
                ViewModelInfo(
                    "v1", "app.CartViewModel", "androidx.lifecycle.ViewModelProvider.DefaultKey:app.CartViewModel", "back stack entry cart",
                    listOf(
                        PropertyInfo("state", "stateflow", "CartState", Json.parseToJsonElement("""{"items":2,"coupon":null}"""), true),
                        PropertyInfo("label", "field", "String", JsonPrimitive("x"), false),
                    ),
                    listOf(PropertyInfo("step", "savedstate", "Integer", JsonPrimitive(1), true)),
                ),
            ),
        )
        assertEquals(
            """
            CartViewModel  v1  (back stack entry cart)
              ✎ state  stateflow  {"items":2,"coupon":null}
                label  field  "x"
              saved state
              ✎ step  1
            """.trimIndent(),
            RuntimeText.viewModels(list),
        )
    }

    @Test
    fun `memory in units`() {
        assertEquals(
            "Java heap 12.0 MB of 256.0 MB · native 4.0 MB · PSS 80.0 MB · 7 collections (35 ms)",
            RuntimeText.memory(MemoryState(12 * 1024 * 1024, 256L * 1024 * 1024, 0, 4 * 1024 * 1024, 0, 80 * 1024, 7, 35)),
        )
    }
}
