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
include(":dta-sidekick")
include(":dta-sidekick-shim")
include(":dta-sidekick-rhino")
