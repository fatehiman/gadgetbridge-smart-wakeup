package nodomain.freeyourgadget.gadgetbridge.sleepwake.core

/** Ready-made JSON text that is embedded as is. */
class RawJson(val text: String)

/** Tiny JSON reader/writer for maps/lists/numbers/strings/booleans/null. Pure Kotlin, so it also runs in unit tests. */
object Json {
    /** Parses JSON into Map / List / Double / Long / String / Boolean / null. Throws IllegalArgumentException on bad input. */
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        require(p.i == text.length) { "trailing data at ${p.i}" }
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("unexpected '$c' at $i")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return v
        }

        private fun num(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDouble()
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c != BACKSLASH) {
                    sb.append(c)
                    continue
                }
                when (val e = s[i++]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    'u' -> {
                        sb.append(s.substring(i, i + 4).toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(e)
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val out = mutableListOf<Any?>()
            skipWs()
            if (s[i] == ']') {
                i++
                return out
            }
            while (true) {
                out += value()
                skipWs()
                val c = s[i++]
                if (c == ']') return out
                require(c == ',') { "expected , at ${i - 1}" }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val out = linkedMapOf<String, Any?>()
            skipWs()
            if (s[i] == '}') {
                i++
                return out
            }
            while (true) {
                skipWs()
                require(s[i] == '"') { "expected key at $i" }
                val k = str()
                skipWs()
                require(s[i++] == ':') { "expected : at ${i - 1}" }
                out[k] = value()
                skipWs()
                val c = s[i++]
                if (c == '}') return out
                require(c == ',') { "expected , at ${i - 1}" }
            }
        }
    }

    private const val BACKSLASH = '\\'

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
                BACKSLASH -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}
