package nodomain.freeyourgadget.gadgetbridge.sleepwake.core

/** Tiny JSON writer for maps/lists/numbers/strings/booleans/null. Pure Kotlin, so it also runs in unit tests. */
/** Ready-made JSON text that is embedded as is. */
class RawJson(val text: String)

object Json {
    fun encode(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is RawJson -> sb.append(v.text.ifBlank { "null" })
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float -> sb.append(num(v.toDouble()))
            is Double -> sb.append(num(v))
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    str(sb, k.toString())
                    sb.append(':')
                    write(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, x)
                }
                sb.append(']')
            }
            is Enum<*> -> str(sb, v.name)
            else -> str(sb, v.toString())
        }
    }

    private fun num(d: Double): String = if (d.isNaN() || d.isInfinite()) "null" else if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
