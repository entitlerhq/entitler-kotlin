package com.entitlerhq.entitler.generator

import com.entitlerhq.entitler.EntitlerApiException
import com.entitlerhq.entitler.EntitlerException
import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.FeatureList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

private const val DEFAULT_OUT = "src/main/kotlin/EntitlerFeatures.kt"

private val usage =
    """
    Usage: entitler generate [options]

    Writes a Kotlin file of typed feature constants from your Entitler catalogue.

    Options:
      --out <file>        The file to write (default $DEFAULT_OUT).
      --package <name>    The Kotlin package to declare (default none).
      --key <key>         An API key with the plans:read scope (default the ENTITLER_KEY environment variable).
      --base-url <url>    The API's base URL (default https://api.entitler.dev).
      --check             Fail when the file is out of date, without writing it.
      --help              Show this help.
    """.trimIndent()

/**
 * Runs `entitler generate`, for example from `java -jar entitler-generator.jar generate --package com.example`.
 * Exits with 0 on success or when the file is up to date, and 1 otherwise.
 */
public fun main(args: Array<String>) {
    exitProcess(run(args.toList(), System.getenv("ENTITLER_KEY"), System.out, System.err))
}

private class Options(
    val values: Map<String, String>,
    val flags: Set<String>,
)

private val valued = setOf("out", "package", "key", "base-url")
private val switches = setOf("check", "help")

private fun parse(args: List<String>): Options {
    val values = mutableMapOf<String, String>()
    val flags = mutableSetOf<String>()
    var index = 0
    while (index < args.size) {
        val arg = args[index++]
        require(arg.startsWith("--")) { "Unexpected argument $arg." }
        val name = arg.removePrefix("--").substringBefore('=')
        when (name) {
            in switches -> {
                flags += name
            }

            in valued -> {
                values[name] =
                    if ('=' in
                        arg
                    ) {
                        arg.substringAfter('=')
                    } else {
                        args.getOrNull(index++) ?: throw IllegalArgumentException("Give --$name a value.")
                    }
            }

            else -> {
                throw IllegalArgumentException("Unknown option --$name.")
            }
        }
    }
    return Options(values, flags)
}

private class Exit(
    val code: Int,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

private fun exit(
    code: Int,
    message: String,
    cause: Throwable? = null,
): Nothing = throw Exit(code, message, cause)

internal fun run(
    args: List<String>,
    environmentKey: String?,
    out: PrintStream,
    err: PrintStream,
): Int =
    try {
        out.println(generate(args, environmentKey))
        0
    } catch (exit: Exit) {
        (if (exit.code == 0) out else err).println(exit.message)
        exit.code
    }

private fun generate(
    args: List<String>,
    environmentKey: String?,
): String {
    if (args.firstOrNull() == "--help") exit(0, usage)
    if (args.firstOrNull() != "generate") exit(1, usage)
    val options =
        try {
            parse(args.drop(1))
        } catch (error: IllegalArgumentException) {
            exit(1, "${error.message}\n$usage", error)
        }
    if ("help" in options.flags) exit(0, usage)
    val key = (options.values["key"] ?: environmentKey)?.trim()
    if (key.isNullOrEmpty()) exit(1, "Provide an API key with --key or set ENTITLER_KEY. The key needs the plans:read scope.")
    val file = File(options.values["out"] ?: DEFAULT_OUT)
    val features = fetch(key, options.values["base-url"])
    val existing = file.takeIf { it.isFile }?.readText()
    val rendered = renderFeatures(features, options.values["package"], existing)
    val count = counted(features.features.size)
    if ("check" in options.flags) {
        if (existing == null || withoutReadFrom(existing) != withoutReadFrom(rendered)) {
            exit(1, "$file is out of date. Run entitler generate to update it.")
        }
        return "$file is up to date with $count."
    }
    file.absoluteFile.parentFile?.mkdirs()
    file.writeText(rendered)
    return "Wrote $count to $file."
}

private fun fetch(
    key: String,
    baseUrl: String?,
): FeatureList =
    try {
        runBlocking {
            EntitlerServer(key) {
                baseUrl?.let { this.baseUrl = it }
                cache = null
            }.use { it.features() }
        }
    } catch (error: EntitlerApiException) {
        exit(1, "Entitler request failed: ${error.message} (${error.code})", error)
    } catch (error: EntitlerException) {
        exit(1, "Entitler request failed: ${error.message}", error)
    }

private fun counted(count: Int): String = if (count == 1) "1 feature" else "$count features"

private fun withoutReadFrom(source: String): String = source.lines().filterNot { it.startsWith(READ_FROM) }.joinToString("\n")
