plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.dokka.javadoc)
    alias(libs.plugins.kover)
    alias(libs.plugins.binary.compatibility)
    alias(libs.plugins.maven.publish)
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":entitler"))
    implementation(libs.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
    testRuntimeOnly(libs.junit.launcher)
}

kover {
    reports {
        verify {
            rule {
                minBound(90)
            }
        }
    }
}

dokka {
    moduleName = "Entitler testing"
    dokkaSourceSets.configureEach {
        reportUndocumented = true
        includes.from("Module.md")
    }
    dokkaPublications.configureEach {
        failOnWarning = true
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates(artifactId = "entitler-testing")
    pom {
        name = "Entitler testing"
        description = "Test doubles and answer builders for apps that use the Entitler SDK for Kotlin."
        inceptionYear = "2026"
    }
}
