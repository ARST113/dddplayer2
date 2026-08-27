pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DDD Video Player"
include(":app")
include(":legacy")

// Just Player 2.1 transition build. The upstream source is fetched by
// scripts/prepare-justplus.sh immediately before Gradle is invoked in CI.
if (providers.gradleProperty("useJustPlus").orNull == "true") {
    val upstream = file(".justplus-upstream")
    require(upstream.isDirectory) {
        "Missing .justplus-upstream. Run scripts/prepare-justplus.sh first."
    }
    include(":justplusapp")
    project(":justplusapp").projectDir = file(".justplus-upstream/app")
    include(":doubletapplayerview")
    project(":doubletapplayerview").projectDir = file(".justplus-upstream/doubletapplayerview")
    include(":android-file-chooser")
    project(":android-file-chooser").projectDir = file(".justplus-upstream/android-file-chooser")
}
