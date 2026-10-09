package com.entitlerhq.examples

import com.entitlerhq.entitler.FeatureList
import com.entitlerhq.entitler.generator.renderFeatures
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

class GeneratedFeaturesTest {
    private val root = File(System.getProperty("entitler.root"))

    @Test
    fun `the generated features example matches the generator's output for the sample catalogue`() {
        val json = Json { ignoreUnknownKeys = true }
        val features =
            json.decodeFromString(
                FeatureList.serializer(),
                File(root, "entitler/src/test/resources/fixtures/features.json").readText(),
            )
        val file = File(root, "examples/src/main/kotlin/com/entitlerhq/examples/generatedfeatures/Features.kt")
        val rendered = renderFeatures(features, "com.entitlerhq.examples.generatedfeatures", file.takeIf { it.isFile }?.readText())
        if (System.getenv("UPDATE_GENERATED") == "1") file.writeText(rendered)
        assertEquals(rendered, file.readText())
    }
}
