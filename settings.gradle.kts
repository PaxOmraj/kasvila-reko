pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }   // v46: DantSu ESC/POS printer library
    }
}
rootProject.name = "KasvilaRekoTilaukset"
include(":app")
include(":ostot")   // v58: Ostot ja varasto -moduuli (docs/OSTOT_TIETOSISALTO.md)
