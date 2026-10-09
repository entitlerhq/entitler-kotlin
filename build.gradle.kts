plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.dokka.javadoc) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.binary.compatibility) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.animalsniffer) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

subprojects {
    group = property("GROUP") as String
    version = property("VERSION_NAME") as String

    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version = rootProject.libs.versions.ktlint
    }

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(listOfNotNull(rootProject.file("config/detekt.yml"), rootProject.file("config/detekt-examples.yml").takeIf { project.name == "examples" }))
    }

    configurations.matching { it.name == "detekt" }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") useVersion("2.0.21")
        }
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "11"
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
                allWarningsAsErrors = true
                freeCompilerArgs.add("-Xjdk-release=11")
            }
        }
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
        tasks.withType<JavaCompile>().configureEach { options.release = 11 }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            systemProperty("junit.jupiter.execution.timeout.default", "60s")
        }
    }
}
