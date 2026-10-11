package com.entitlerhq.entitler.generator

import com.entitlerhq.entitler.EntitlerApiException
import com.entitlerhq.entitler.EntitlerConnectionException
import com.entitlerhq.entitler.EntitlerException
import com.entitlerhq.entitler.EntitlerServer
import com.entitlerhq.entitler.EntitlerTimeoutException
import com.entitlerhq.entitler.ErrorCode
import com.entitlerhq.entitler.FeatureList
import com.entitlerhq.entitler.SnapshotKeys
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

private const val DEFAULT_OUT = "src/main/kotlin/EntitlerFeatures.kt"
private const val DEFAULT_KEYS_OUT = "entitler-snapshot-keys.json"

private val usage =
    """
    Usage: java -jar entitler-generator.jar <command> [options]

    Commands:
      generate        Writes a Kotlin file of typed feature constants from your Entitler catalogue.
      snapshot-keys   Writes the keys that verify offline snapshots, for your app to ship.

    Options of generate:
      --out <file>        The file to write (default $DEFAULT_OUT).
      --package <name>    The Kotlin package to declare (default none).
      --key <key>         An API key with the plans:read scope (default the ENTITLER_KEY environment variable).
      --base-url <url>    The API's base URL (default https://api.entitler.dev).
      --check             Fail when the file is out of date, without writing it.
      --allow-empty       Write the file even when Entitler lists no features.
      --help              Show this help.

    Options of snapshot-keys:
      --out <file>        The file to write (default $DEFAULT_KEYS_OUT).
      --base-url <url>    The API's base URL (default https://api.entitler.dev).
      --help              Show this help.
    """.trimIndent()

/**
 * Runs the `generate` or `snapshot-keys` command, for example
 * `java -jar entitler-generator.jar generate --package com.example`. Exits with 0 on success or when the
 * file is up to date, and 1 otherwise.
 */
public fun main(args: Array<String>) {
    exitProcess(run(args.toList(), System.getenv("ENTITLER_KEY"), System.out, System.err))
}

private class Options(
    val values: Map<String, String>,
    val flags: Set<String>,
)

private fun parse(
    args: List<String>,
    valued: Set<String>,
    switches: Set<String>,
): Options {
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
        val message =
            when (args.firstOrNull()) {
                "--help" -> {
                    exit(0, usage)
                }

                "generate" -> {
                    generate(
                        options(args, setOf("out", "package", "key", "base-url"), setOf("check", "allow-empty", "help")),
                        environmentKey,
                    )
                }

                "snapshot-keys" -> {
                    snapshotKeys(options(args, setOf("out", "base-url"), setOf("help")))
                }

                else -> {
                    exit(1, usage)
                }
            }
        out.println(message)
        0
    } catch (exit: Exit) {
        (if (exit.code == 0) out else err).println(exit.message)
        exit.code
    }

private fun options(
    args: List<String>,
    valued: Set<String>,
    switches: Set<String>,
): Options {
    val options =
        try {
            parse(args.drop(1), valued, switches)
        } catch (error: IllegalArgumentException) {
            exit(1, "${error.message}\n$usage", error)
        }
    if ("help" in options.flags) exit(0, usage)
    return options
}

private fun generate(
    options: Options,
    environmentKey: String?,
): String {
    val key = (options.values["key"] ?: environmentKey)?.trim()
    if (key.isNullOrEmpty()) exit(1, "Provide an API key with --key or set ENTITLER_KEY. The key needs the plans:read scope.")
    val file = File(options.values["out"] ?: DEFAULT_OUT)
    val features = fetch(key, options.values["base-url"])
    val existing = file.takeIf { it.isFile }?.readText()
    val wouldEmpty = features.features.isEmpty() && "allow-empty" !in options.flags
    if (wouldEmpty && holdsConstants(existing)) {
        exit(1, "Entitler listed no features, so $file was left as it is. Check the key's environment, or pass --allow-empty.")
    }
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
    request {
        EntitlerServer(key) {
            baseUrl?.let { this.baseUrl = it }
            cache = null
        }.use { it.features() }
    }

private fun <T> request(block: suspend () -> T): T =
    try {
        runBlocking { block() }
    } catch (error: EntitlerException) {
        exit(1, requestFailure(error), error)
    } catch (error: IllegalArgumentException) {
        exit(1, error.message.orEmpty(), error)
    }

internal fun requestFailure(error: EntitlerException): String {
    val code =
        when (error) {
            is EntitlerApiException -> error.code.value
            is EntitlerTimeoutException -> ErrorCode.TimedOut.value
            is EntitlerConnectionException -> ErrorCode.ConnectionFailed.value
            else -> null
        }
    val message = error.message.orEmpty().removeSuffix(".")
    return if (code == null) "$message." else "Entitler request failed: $message ($code)."
}

private fun snapshotKeys(options: Options): String {
    val file = File(options.values["out"] ?: DEFAULT_KEYS_OUT)
    val keys =
        request {
            EntitlerServer(UNUSED_KEY) {
                options.values["base-url"]?.let { baseUrl = it }
                cache = null
            }.use { it.snapshotKeys() }
        }
    if (keys.keys.isEmpty()) exit(1, "Entitler published no snapshot keys, so $file was left as it is.")
    val target = file.absoluteFile
    target.parentFile?.mkdirs()
    val partial = File.createTempFile(".entitler-snapshot-keys", ".json", target.parentFile)
    partial.writeText(snapshotKeysJson(keys))
    java.nio.file.Files.move(
        partial.toPath(),
        target.toPath(),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
    )
    val count = if (keys.keys.size == 1) "1 snapshot key" else "${keys.keys.size} snapshot keys"
    return "Wrote $count to $file."
}

private const val UNUSED_KEY = "ent_snapshot_keys_need_no_key"

internal fun snapshotKeysJson(keys: SnapshotKeys): String {
    val items =
        keys.keys.joinToString(",\n") { key ->
            val members =
                listOf("kty" to key.kty, "crv" to key.crv, "x" to key.x, "y" to key.y, "kid" to key.kid, "alg" to key.alg, "use" to key.use)
            members.joinToString(",\n", "    {\n", "\n    }") { (name, value) -> "      ${jsonString(name)}: ${jsonString(value)}" }
        }
    return "{\n  \"keys\": [\n$items\n  ]\n}\n"
}

private fun jsonString(value: String): String =
    buildString {
        append('"')
        for (char in value) {
            when {
                char == '"' || char == '\\' -> append('\\').append(char)
                char < ' ' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }

private fun holdsConstants(source: String?): Boolean =
    source != null && Regex("""val\s+`?[A-Za-z_][A-Za-z0-9_]*`?\s*:\s*Feature\.""").containsMatchIn(source)

private fun counted(count: Int): String = if (count == 1) "1 feature" else "$count features"

private fun withoutReadFrom(source: String): String = source.lines().filterNot { it.startsWith(READ_FROM) }.joinToString("\n")
