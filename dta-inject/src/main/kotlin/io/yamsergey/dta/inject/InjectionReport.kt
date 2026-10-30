package io.yamsergey.dta.inject

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** What injection did for one app project (contracts/injection.md). */
@Serializable
enum class InjectionOutcome {
    /** Added to `debugImplementation`. */
    @SerialName("injected") INJECTED,
    /** The project declares DTA itself; nothing added. */
    @SerialName("project-provided") PROJECT_PROVIDED,
    /** The sidekick could not be resolved; nothing added, the build went on. */
    @SerialName("unresolved") UNRESOLVED,
    /** The script could not handle this project; nothing added, the build went on. */
    @SerialName("unsupported") UNSUPPORTED,
}

@Serializable
data class ProjectInjection(val path: String, val outcome: InjectionOutcome, val reason: String? = null)

/** The report the init script writes at the end of configuration, one entry per app project. */
@Serializable
data class InjectionReport(val version: String, val projects: List<ProjectInjection>) {
    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Reads a report; one that does not match the contract is refused with the reason. */
        fun parse(text: String): InjectionReport = try {
            JSON.decodeFromString(serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("not a DTA injection report: ${e.message}", e)
        }
    }
}
