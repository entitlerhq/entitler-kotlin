plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    alias(libs.plugins.dokka.javadoc)
    alias(libs.plugins.kover)
    alias(libs.plugins.binary.compatibility)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.animalsniffer)
}

kotlin {
    explicitApi()
}

dependencies {
    api(libs.okhttp)
    api(libs.coroutines.core)
    implementation(libs.serialization.json)

    signature(libs.android.signature) {
        artifact {
            classifier = "coreLib2"
            type = "signature"
        }
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
    testRuntimeOnly(libs.junit.launcher)
}

val generateVersion by tasks.registering {
    val version = project.version.toString()
    val output = layout.buildDirectory.dir("generated/version")
    inputs.property("version", version)
    outputs.dir(output)
    doLast {
        val file = output.get().file("com/entitlerhq/entitler/Version.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package com.entitlerhq.entitler\n\ninternal const val SDK_VERSION = \"$version\"\n")
    }
}

kotlin.sourceSets.main {
    kotlin.srcDir(generateVersion)
}

kotlin.sourceSets.test {
    languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
}

animalsniffer {
    sourceSets = listOf(project.sourceSets.main.get())
}

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs the live API tests against ENTITLER_TEST_KEY."
    group = "verification"
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    outputs.upToDateWhen { false }
}

kover {
    reports {
        filters {
            excludes {
                classes("com.entitlerhq.entitler.VersionKt", "*\$\$serializer")
            }
        }
        verify {
            rule {
                minBound(90)
            }
        }
    }
}

dokka {
    moduleName = "Entitler"
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
    signAllPublications()
    coordinates(artifactId = "entitler")
    pom {
        name = "Entitler"
        description = "The official Entitler SDK for Kotlin: feature checks, usage, pricing and billing."
        inceptionYear = "2026"
    }
}
