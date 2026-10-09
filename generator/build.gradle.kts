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
    implementation(project(":entitler"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockwebserver)
    testRuntimeOnly(libs.junit.launcher)
}

val mainClassName = "com.entitlerhq.entitler.generator.MainKt"

tasks.jar {
    manifest { attributes("Main-Class" to mainClassName) }
}

val fatJar by tasks.registering(Jar::class) {
    description = "Builds the runnable generator JAR with its dependencies."
    group = "build"
    archiveClassifier = "all"
    manifest { attributes("Main-Class" to mainClassName) }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { classpath -> classpath.map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
}

tasks.assemble { dependsOn(fatJar) }

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
    moduleName = "Entitler generator"
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
    coordinates(artifactId = "entitler-generator")
    pom {
        name = "Entitler generator"
        description = "Generates typed Entitler feature constants for Kotlin projects."
        inceptionYear = "2026"
    }
}

publishing.publications.withType<MavenPublication>().configureEach {
    artifact(fatJar)
}
