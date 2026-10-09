package com.entitlerhq.entitler.generator

import com.entitlerhq.entitler.Environment
import com.entitlerhq.entitler.EnvironmentKind
import com.entitlerhq.entitler.FeatureList
import com.entitlerhq.entitler.FeatureListing
import com.entitlerhq.entitler.FeatureType
import com.entitlerhq.entitler.Track
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeneratorTest {
    private val environment = Environment("e_1", "development", EnvironmentKind.Test)
    private val track = Track("j_1", "All customers")

    private fun feature(
        key: String,
        type: FeatureType,
        name: String = key,
        description: String = "",
        unit: String = "",
        archived: Boolean = false,
        includes: List<String> = emptyList(),
    ) = FeatureListing("f_$key", key, name, type, description, unit, null, archived, includes)

    private val sample =
        listOf(
            feature("ai_credits", FeatureType.Metered, "AI credits", "Spent on AI actions each month.", "credits"),
            feature(
                "collaboration",
                FeatureType.Group,
                "Collaboration",
                "Working with other people.",
                includes = listOf("team_seats", "shared_folders"),
            ),
            feature("export_pdf", FeatureType.Boolean, "Export to PDF", "Download any document as a PDF.\nEvery page."),
            feature("shared_folders", FeatureType.Boolean, "Shared folders"),
            feature("team_seats", FeatureType.Config, "Team seats", unit = "seats"),
            feature(
                "team_essentials",
                FeatureType.Group,
                "Team essentials",
                includes = listOf("collaboration", "sso", "missing", "team_seats"),
            ),
            feature("sso", FeatureType.Boolean, "", archived = true),
            feature("quantum", FeatureType("quantum"), "Quantum"),
        )

    private fun golden(name: String): String = javaClass.getResource("/golden/$name.kt.txt")!!.readText()

    @Test
    fun `renders constants, docs, deprecation and group leaves`() {
        assertEquals(golden("sample"), renderFeatures(FeatureList(sample, environment, track, 2, null), "com.example"))
    }

    @Test
    fun `renders an empty list and a track following a change, without a package`() {
        assertEquals(golden("empty"), renderFeatures(FeatureList(emptyList(), environment, Track("j_2", "Beta"), null, "c_1")))
    }

    @Test
    fun `escapes reserved words and taken names deterministically`() {
        val features =
            listOf(
                feature("in", FeatureType.Boolean),
                feature("export_pdf", FeatureType.Boolean),
                feature("export__pdf", FeatureType.Boolean),
                feature("export_pdf_", FeatureType.Boolean),
                feature("object", FeatureType.Boolean),
            )
        assertEquals(golden("escaped"), renderFeatures(FeatureList(features, environment, track, 1, null)))
    }

    @Test
    fun `keeps existing names so adding a feature never renames another`() {
        val existing =
            """
            public object Features {
                public val pdf: Feature.OnOff = Feature.OnOff("export_pdf")
                public val `gone`: Feature.OnOff = Feature.OnOff("removed")
                public val exportPdfV2: Feature.OnOff = Feature.OnOff("export_pdf_v_2")
            }
            """.trimIndent()
        val features =
            listOf(
                feature("export_pdf", FeatureType.Boolean),
                feature("pdf", FeatureType.Boolean),
                feature("export_pdf_v_2", FeatureType.Boolean),
            )
        val rendered = renderFeatures(FeatureList(features, environment, track, 1, null), existing = existing)
        assertTrue(rendered.contains("public val pdf: Feature.OnOff = Feature.OnOff(\"export_pdf\")"))
        assertTrue(rendered.contains("public val pdf2: Feature.OnOff = Feature.OnOff(\"pdf\")"))
        assertTrue(rendered.contains("public val exportPdfV2: Feature.OnOff = Feature.OnOff(\"export_pdf_v_2\")"))
        assertFalse(rendered.contains("removed"))
    }

    @Test
    fun `wraps long group constants the way the formatter would`() {
        val members = (1..12).map { feature("member_feature_number_$it", FeatureType.Boolean) }
        val group = feature("everything_in_the_catalogue", FeatureType.Group, includes = members.map { it.key })
        assertEquals(golden("wrapped"), renderFeatures(FeatureList(members + group, environment, track, 1, null)))
        val medium = (1..3).map { feature("medium_member_$it", FeatureType.Boolean) }
        val mediumGroup = feature("a_medium_length_group_key_for_wrapping", FeatureType.Group, includes = medium.map { it.key })
        val rendered = renderFeatures(FeatureList(medium + mediumGroup, environment, track, 1, null))
        assertTrue(rendered.contains("public val aMediumLengthGroupKeyForWrapping: Feature.Group =\n        Feature.Group("))
    }

    @Test
    fun `groups stop at cycles`() {
        val features =
            listOf(
                feature("a", FeatureType.Group, includes = listOf("b", "leaf")),
                feature("b", FeatureType.Group, includes = listOf("a", "leaf", "other")),
                feature("leaf", FeatureType.Boolean),
                feature("other", FeatureType.Boolean),
            )
        val rendered = renderFeatures(FeatureList(features, environment, track, 1, null))
        assertTrue(rendered.contains("public val a: Feature.Group = Feature.Group(\"a\", listOf(\"leaf\", \"other\"))"))
        assertTrue(rendered.contains("public val b: Feature.Group = Feature.Group(\"b\", listOf(\"leaf\", \"other\"))"))
    }

    @Test
    fun `names convert keys to camel case`() {
        assertEquals("exportPdf", camelCase("export_pdf"))
        assertEquals("ai2x", camelCase("ai_2x"))
        assertEquals("feature", camelCase("_"))
    }
}

class CommandTest {
    private val api = MockWebServer().apply { start() }

    @TempDir
    lateinit var directory: File

    @AfterEach
    fun close() = api.close()

    private val features =
        """{"environment":{"id":"e_1","name":"development","kind":"test"},"track":{"id":"j_1","name":"All customers"},"release":RELEASE,"change":null,
        "features":[FEATURES]}"""

    private fun reply(
        release: Int = 2,
        count: Int = 2,
    ) {
        val listed =
            (1..count).joinToString(",") {
                """{"id":"f$it","key":"feature_$it","name":"Feature $it","type":"boolean","description":"","unit":"",""" +
                    """"resetEvery":null,"archived":false,"includes":[]}"""
            }
        api.enqueue(MockResponse.Builder().body(features.replace("RELEASE", "$release").replace("FEATURES", listed)).build())
    }

    private class Result(
        val code: Int,
        val out: String,
        val err: String,
    )

    private fun run(
        vararg args: String,
        key: String? = "ent_test_key",
    ): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = run(args.toList(), key, PrintStream(out, true), PrintStream(err, true))
        return Result(code, out.toString().trim(), err.toString().trim())
    }

    private val base get() = api.url("/").toString()

    @Test
    fun `writes the file and reports how many features`() {
        val file = File(directory, "src/Features.kt")
        reply(count = 2)
        val result = run("generate", "--out", file.path, "--base-url", base, "--package=com.example")
        assertEquals(0, result.code)
        assertEquals("Wrote 2 features to $file.", result.out)
        assertTrue(file.readText().contains("package com.example"))
        assertEquals("Bearer ent_test_key", api.takeRequest().headers["Authorization"])
        reply(count = 1)
        assertEquals("Wrote 1 feature to $file.", run("generate", "--out=${file.path}", "--base-url", base).out)
    }

    @Test
    fun `check ignores the Read from line and fails when out of date`() {
        val file = File(directory, "Features.kt")
        reply(release = 2)
        run("generate", "--out", file.path, "--base-url", base)
        reply(release = 3)
        val upToDate = run("generate", "--check", "--out", file.path, "--base-url", base)
        assertEquals(0, upToDate.code)
        assertEquals("$file is up to date with 2 features.", upToDate.out)
        reply(count = 3)
        val outOfDate = run("generate", "--check", "--out", file.path, "--base-url", base)
        assertEquals(1, outOfDate.code)
        assertEquals("$file is out of date. Run entitler generate to update it.", outOfDate.err)
        reply()
        assertEquals(1, run("generate", "--check", "--out", File(directory, "missing.kt").path, "--base-url", base).code)
    }

    @Test
    fun `a missing key fails with the documented message`() {
        val result = run("generate", key = null)
        assertEquals(1, result.code)
        assertEquals("Provide an API key with --key or set ENTITLER_KEY. The key needs the plans:read scope.", result.err)
        assertEquals(1, run("generate", "--key", " ", key = null).code)
    }

    @Test
    fun `API failures are reported with their code`() {
        api.enqueue(
            MockResponse
                .Builder()
                .code(403)
                .body("""{"error":{"code":"scope_required","message":"Needs plans:read."}}""")
                .build(),
        )
        val result = run("generate", "--key", "ent_test_other", "--base-url", base, "--out", File(directory, "F.kt").path)
        assertEquals(1, result.code)
        assertEquals("Entitler request failed: Needs plans:read. (scope_required)", result.err)
        assertEquals("Bearer ent_test_other", api.takeRequest().headers["Authorization"])
    }

    @Test
    fun `connection failures are reported`() {
        val closed = api.url("/").toString()
        api.close()
        val result = run("generate", "--base-url", closed, "--out", File(directory, "F.kt").path)
        assertEquals(1, result.code)
        assertTrue(result.err.startsWith("Entitler request failed: "))
    }

    @Test
    fun `help prints usage and exits 0, no command prints usage and exits 1`() {
        assertEquals(0, run("--help").code)
        assertTrue(run("--help").out.startsWith("Usage: entitler generate"))
        assertEquals(0, run("generate", "--help").code)
        val none = run()
        assertEquals(1, none.code)
        assertTrue(none.err.startsWith("Usage: entitler generate"))
        assertEquals(1, run("publish").code)
    }

    @Test
    fun `bad options print why with usage and exit 1`() {
        assertEquals("Unknown option --colour.", run("generate", "--colour").err.lines().first())
        assertEquals("Give --out a value.", run("generate", "--out").err.lines().first())
        assertEquals("Unexpected argument stray.", run("generate", "stray").err.lines().first())
    }
}
