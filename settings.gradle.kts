pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dta"

include(":dta-protocol")
include(":dta-daemon")
include(":dta-client")
include(":dta-cli")
include(":dta-present")
include(":dta-mcp")
include(":dta-inject")
include(":dta-run")
include(":dta-studio")
include(":dta-sidekick")
include(":dta-sidekick-shim")
include(":dta-sidekick-rhino")
include(":dta-state")
include(":dta-state-noop")
