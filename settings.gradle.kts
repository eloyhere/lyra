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
        maven("https://jitpack.io")
    }
}

rootProject.name = "Lyra"

include(":app")
include(":core:model")
include(":core:policy")
include(":core:amp")
include(":core:agent")
include(":core:data")
include(":core:network")
include(":core:attention")
include(":core:designsystem")
include(":core:accessibility")
