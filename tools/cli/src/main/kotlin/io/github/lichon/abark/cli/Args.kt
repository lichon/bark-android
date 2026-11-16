package io.github.lichon.abark.cli

/**
 * Tiny `--flag value` parser. A real CLI library would be more code than the CLI needs, and Gradle's `run` task makes
 * quoting fragile enough already.
 */
class Args(private val values: Map<String, String>, private val flags: Set<String>) {

    fun string(name: String): String? = values[name]

    fun string(name: String, default: String): String = values[name] ?: default

    fun required(name: String): String =
        values[name] ?: throw UsageException("missing required option --$name")

    fun int(name: String, default: Int): Int = values[name]?.toIntOrNull() ?: default

    fun long(name: String, default: Long): Long = values[name]?.toLongOrNull() ?: default

    fun flag(name: String): Boolean = flags.contains(name)

    companion object {
        /** `--name value`, `--name=value`, and bare `--flag`. */
        fun parse(args: List<String>): Args {
            val values = LinkedHashMap<String, String>()
            val flags = LinkedHashSet<String>()
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                if (!arg.startsWith("--")) throw UsageException("unexpected argument: $arg")
                val body = arg.removePrefix("--")
                if (body.contains('=')) {
                    val (name, value) = body.split('=', limit = 2)
                    values[name] = value
                } else if (index + 1 < args.size && !args[index + 1].startsWith("--")) {
                    values[body] = args[index + 1]
                    index++
                } else {
                    flags += body
                }
                index++
            }
            return Args(values, flags)
        }
    }
}

class UsageException(message: String) : Exception(message)

internal fun log(message: String) {
    val stamp = java.time.LocalTime.now().withNano(0)
    println("[$stamp] $message")
}

internal fun fail(message: String, code: Int = 1): Nothing {
    System.err.println("error: $message")
    kotlin.system.exitProcess(code)
}
