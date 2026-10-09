plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":entitler"))
    implementation(project(":entitler-generator"))
    implementation(libs.ktor.server.netty)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.serialization.json)
    testRuntimeOnly(libs.junit.launcher)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        freeCompilerArgs.set(listOf("-Xjdk-release=17"))
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach { options.release = 17 }

mapOf(
    "quickstart" to "quickstart",
    "pricingPage" to "pricingpage",
    "inApp" to "inapp",
    "meteredWork" to "meteredwork",
    "offline" to "offline",
    "billing" to "billing",
    "generatedFeatures" to "generatedfeatures",
    "ktor" to "ktor",
).forEach { (task, folder) ->
    tasks.register<JavaExec>(task) {
        description = "Runs the $task example with ENTITLER_KEY."
        group = "examples"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = "com.entitlerhq.examples.$folder.MainKt"
    }
}

tasks.test {
    inputs.files(fileTree(rootDir) { include("README.md", "docs/**/*.md") })
    systemProperty("entitler.root", rootDir.absolutePath)
}
