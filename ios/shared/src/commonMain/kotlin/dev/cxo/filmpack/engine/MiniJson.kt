package dev.cxo.filmpack.engine

/** Just enough JSON for the bundled catalogs (arrays of flat objects). No dependencies. */
internal object MiniJson {
    fun parse(text: String): Any? = Parser(text).run { val v = value(); skip(); require(i == s.length) { "Trailing JSON" }; v }

    @Suppress("UNCHECKED_CAST")
    fun rows(text: String): List<Map<String, Any?>> = (parse(text) as List<Map<String, Any?>>)

    private class Parser(val s: String) {
        var i = 0
        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            skip()
            return when (val c = s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> lit("true", true); 'f' -> lit("false", false); 'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("Bad JSON at $i")
            }
        }
        fun lit(w: String, v: Any?): Any? { require(s.startsWith(w, i)); i += w.length; return v }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
        fun str(): String {
            i++; val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r')
                        'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                    else -> b.append(c)
                }
            }
        }
        fun arr(): List<Any?> {
            i++; val out = ArrayList<Any?>(); skip()
            if (s[i] == ']') { i++; return out }
            while (true) { out.add(value()); skip(); if (s[i] == ',') { i++; continue }; require(s[i] == ']'); i++; return out }
        }
        fun obj(): Map<String, Any?> {
            i++; val out = LinkedHashMap<String, Any?>(); skip()
            if (s[i] == '}') { i++; return out }
            while (true) {
                skip(); val k = str(); skip(); require(s[i++] == ':'); out[k] = value(); skip()
                if (s[i] == ',') { i++; continue }; require(s[i] == '}'); i++; return out
            }
        }
    }
}
