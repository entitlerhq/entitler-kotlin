pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "entitler-kotlin"

include(":entitler", ":entitler-generator", ":examples")
project(":entitler-generator").projectDir = file("generator")
