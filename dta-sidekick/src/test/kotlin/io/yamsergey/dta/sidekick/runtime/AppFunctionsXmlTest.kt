package io.yamsergey.dta.sidekick.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class AppFunctionsXmlTest {
    // As the AppFunctions 1.0.0-beta01 compiler writes it: parameters and data types have <id>s too.
    private val xml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <appfunctions>
            <appfunction>
                <id>app.WeatherFunctions#findPlaces</id>
                <enabledByDefault>true</enabledByDefault>
                <description>Finds places by name.</description>
                <parameters>
                    <dataTypeMetadata><id>unused</id><isNullable>false</isNullable><type>8</type></dataTypeMetadata>
                    <id>unused</id>
                    <name>query</name>
                    <description>Part of the name.</description>
                </parameters>
                <response><valueType><id>unused</id></valueType></response>
            </appfunction>
            <appfunction>
                <id>app.WeatherFunctions#getForecast</id>
                <description>Gets the weather &amp; more.</description>
            </appfunction>
        </appfunctions>
    """.trimIndent()

    @Test
    fun `only each function's own id and description are read, not its parameters'`() {
        assertEquals(
            listOf("app.WeatherFunctions#findPlaces" to "Finds places by name.", "app.WeatherFunctions#getForecast" to "Gets the weather & more."),
            AppFunctionsXml.functions(xml),
        )
    }
}
