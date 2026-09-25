import java.util.Properties

// dl.google.com (Google Maven) is unreachable from some networks. Setting
// zarp.googleMirror=<url> in local.properties or gradle.properties, or the
// ZARP_GOOGLE_MIRROR environment variable, adds a mirror after google().
val googleMirror: String? = run {
    val local = Properties()
    val file = settingsDir.resolve("local.properties")
    if (file.exists()) file.inputStream().use { local.load(it) }
    local.getProperty("zarp.googleMirror")
        ?: providers.gradleProperty("zarp.googleMirror").orNull
        ?: System.getenv("ZARP_GOOGLE_MIRROR")
}?.takeIf { it.isNotBlank() }

fun RepositoryHandler.googleWithMirror() {
    google {
        content {
            includeGroupByRegex("com[.]android.*")
            includeGroupByRegex("com[.]google.*")
            includeGroupByRegex("androidx.*")
        }
    }
    if (googleMirror != null) {
        maven(googleMirror) {
            name = "googleMirror"
            content {
                includeGroupByRegex("com[.]android.*")
                includeGroupByRegex("com[.]google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

pluginManagement {
    val mirror: String? = run {
        val local = java.util.Properties()
        val file = settingsDir.resolve("local.properties")
        if (file.exists()) file.inputStream().use { local.load(it) }
        local.getProperty("zarp.googleMirror") ?: System.getenv("ZARP_GOOGLE_MIRROR")
    }?.takeIf { it.isNotBlank() }
    repositories {
        google()
        if (mirror != null) maven(mirror)
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        googleWithMirror()
        mavenCentral()
    }
}

rootProject.name = "Zarp"
include(":app")
