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
    /** Injection was turned off for this build (`dta run --no-inject`); nothing added. */
    @SerialName("off") OFF,
}

/** What injection did about Compose's tooling data library for one app project (injection v2). */
@Serializable
enum class ToolingOutcome {
    /** Added to the debuggable build types, in the app's Compose UI version. */
    @SerialName("injected") INJECTED,
    /** The project declares ui-tooling or ui-tooling-data itself. */
    @SerialName("project-provided") PROJECT_PROVIDED,
    /** The project does not use Compose. */
    @SerialName("not-applicable") NOT_APPLICABLE,
    /** It could not be resolved; nothing added. */
    @SerialName("unresolved") UNRESOLVED,
    /** Tooling injection was turned off. */
    @SerialName("off") OFF,
}

@Serializable
data class ToolingInjection(val outcome: ToolingOutcome, val version: String? = null, val reason: String? = null)

/** One variant of an app project, as the Android Gradle Plugin declares it. */
@Serializable
data class VariantInfo(
    val name: String,
    val buildType: String,
    val flavors: List<String> = emptyList(),
    val debuggable: Boolean,
    val applicationId: String? = null,
)

@Serializable
data class ProjectInjection(
    val path: String,
    val outcome: InjectionOutcome,
    val reason: String? = null,
    val tooling: ToolingInjection? = null,
    val variants: List<VariantInfo> = emptyList(),
)

/** How run mode (`dta run`) resolved the module and variant to build. */
@Serializable
enum class RunOutcome {
    @SerialName("selected") SELECTED,
    @SerialName("no-app-module") NO_APP_MODULE,
    @SerialName("module-required") MODULE_REQUIRED,
    @SerialName("module-not-found") MODULE_NOT_FOUND,
    @SerialName("variant-required") VARIANT_REQUIRED,
    @SerialName("variant-not-found") VARIANT_NOT_FOUND,
    @SerialName("not-debuggable") NOT_DEBUGGABLE,
}

/** [choices] lists the modules or variants to pick from when a selection is needed. */
@Serializable
data class RunSelection(
    val outcome: RunOutcome,
    val module: String? = null,
    val variant: String? = null,
    val task: String? = null,
    val applicationId: String? = null,
    val choices: List<String>? = null,
    val reason: String? = null,
)

/**
 * The report the init script writes at the end of configuration, one entry per app project;
 * in run mode also the selection. Fields added in v2 are optional so a 009 report still reads.
 */
@Serializable
data class InjectionReport(val version: String, val projects: List<ProjectInjection>, val run: RunSelection? = null) {
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
