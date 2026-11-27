package io.yamsergey.dta.cli

import io.yamsergey.dta.protocol.ops.ParamSpec
import io.yamsergey.dta.protocol.ops.ParamType
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** A positional argument of a numeric type reaches the daemon as a number (feature 074: `runtime heap fields 4294967297`). */
class PositionalTypesTest {
    private fun p(type: ParamType) = ParamSpec("x", type, "d", positional = true)

    @Test fun longAndIntPositionalsAreNumbers() {
        assertEquals(JsonPrimitive(4294967297), positionalJson(p(ParamType.LONG), "4294967297"))
        assertEquals(JsonPrimitive(42), positionalJson(p(ParamType.INT), "42"))
    }

    @Test fun nonNumbersStayTextSoTheDaemonNamesTheMistake() {
        assertEquals(JsonPrimitive("abc"), positionalJson(p(ParamType.LONG), "abc"))
        assertEquals(JsonPrimitive("s1"), positionalJson(p(ParamType.STRING), "s1"))
    }
}
