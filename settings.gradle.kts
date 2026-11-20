pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // Compile-only Xposed API 82; this repository is consulted for nothing else.
        exclusiveContent {
            forRepository { maven("https://api.xposed.info/") }
            filter { includeGroup("de.robv.android.xposed") }
        }
    }
}

rootProject.name = "FizzFade"
include(":app")
