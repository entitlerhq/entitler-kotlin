package com.entitlerhq.examples

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

class SnippetsTest {
    private val root = File(System.getProperty("entitler.root"))
    private val fence = Regex("```(kotlin|java)\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

    private fun normalised(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private val sources: List<List<String>> =
        File(root, "examples/src")
            .walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .map { normalised(it.readText()) }
            .toList()

    private fun appears(snippet: List<String>): Boolean = sources.any { lines -> lines.windowed(snippet.size).any { it == snippet } }

    @Test
    fun `every Kotlin and Java snippet in the README and the guides is copied from an example that CI builds`() {
        val documents = listOf(File(root, "README.md")) + File(root, "docs").listFiles().orEmpty().filter { it.extension == "md" }
        var checked = 0
        for (document in documents) {
            for (match in fence.findAll(document.readText())) {
                val snippet = normalised(match.groupValues[2])
                if (!appears(snippet)) fail("A snippet in ${document.name} is not in any example:\n${match.groupValues[2]}")
                checked++
            }
        }
        assertTrue(checked > 20, "Only $checked snippets were found.")
    }
}
