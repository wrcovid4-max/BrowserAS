pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // GeckoView (Gecko engine) is published by Mozilla, not on Google or Central.
        maven("https://maven.mozilla.org/maven2/") { content { includeGroup("org.mozilla.geckoview") } }
    }
}
rootProject.name = "TheBrowserAS"
include(":core", ":engine-chromium", ":app")
// Gecko engine is optional: add `hydrogen.gecko=false` to gradle.properties to build without it (smaller app, Chromium only).
if (providers.gradleProperty("hydrogen.gecko").orNull != "false") include(":engine-gecko")
