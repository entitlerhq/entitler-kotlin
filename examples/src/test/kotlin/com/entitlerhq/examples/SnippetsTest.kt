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

    @Test
    fun `docs, samples and the README name the generator exactly as users run it`() {
        val texts =
            listOf(File(root, "README.md"), File(root, "generator/Module.md")) +
                File(root, "docs").listFiles().orEmpty().filter { it.extension == "md" } +
                File(root, "examples/src/main").walkTopDown().filter { it.isFile }.toList()
        val bare = Regex("""(?<![\w-])entitler (generate|snapshot-keys)\b""")
        val jar = Regex("""java -jar (\S+)""")
        for (file in texts) {
            for (line in file.readLines()) {
                assertTrue(!bare.containsMatchIn(line), "${file.name} names the command bare: $line")
                jar.findAll(line).forEach { assertTrue(it.groupValues[1] == "entitler-generator.jar", "${file.name}: $line") }
            }
        }
    }
}
