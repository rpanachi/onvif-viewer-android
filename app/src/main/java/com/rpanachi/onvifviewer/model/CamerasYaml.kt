package com.rpanachi.onvifviewer.model

/**
 * Reads and writes the camera list in the YAML layout used by cameras.yaml:
 *
 * cameras:
 *   key:
 *     enabled: true
 *     name: Display name
 *     vendor: optional
 *     input: rtsp://user:pass@host:554/sub-stream
 *     main: rtsp://user:pass@host:554/main-stream   (optional)
 *
 * This is a deliberately small reader (nested block mappings of scalars, quotes and comments)
 * so the app has no YAML library dependency.
 */
object CamerasYaml {

    fun parse(text: String): List<Camera> {
        val root = parseTree(text)
        val section = root["cameras"] as? Map<*, *>
            ?: throw IllegalArgumentException("file has no 'cameras:' section")
        val taken = HashSet<String>()
        val out = ArrayList<Camera>()
        for ((key, value) in section) {
            val m = value as? Map<*, *> ?: continue
            val input = m["input"]?.toString()?.trim().orEmpty()
            if (input.isEmpty()) continue
            val id = Camera.uniqueId(Camera.slug(key.toString()), taken)
            taken += id
            out += Camera(
                id = id,
                name = m["name"]?.toString()?.trim().orEmpty().ifEmpty { key.toString() },
                enabled = !m["enabled"].toString().trim().equals("false", ignoreCase = true),
                vendor = m["vendor"]?.toString()?.trim().orEmpty(),
                input = input,
                main = m["main"]?.toString()?.trim().orEmpty(),
            )
        }
        return out
    }

    fun dump(cameras: List<Camera>): String = buildString {
        appendLine("# ONVIF Viewer camera list. Credentials are stored in plain text: keep this file private.")
        appendLine("# input = RTSP sub stream (shown in the grid); main = optional RTSP main stream (single view).")
        appendLine("cameras:")
        for (c in cameras) {
            appendLine("  ${c.id}:")
            appendLine("    enabled: ${c.enabled}")
            appendLine("    name: ${scalar(c.name)}")
            if (c.vendor.isNotBlank()) appendLine("    vendor: ${scalar(c.vendor)}")
            appendLine("    input: ${scalar(c.input)}")
            if (c.main.isNotBlank()) appendLine("    main: ${scalar(c.main)}")
        }
    }

    private val reserved = setOf("true", "false", "null", "yes", "no", "on", "off", "~")

    private fun scalar(s: String): String {
        val plainOk = s.isNotEmpty() && s == s.trim() &&
            !s.contains('#') && !s.contains('"') && !s.contains('\n') &&
            !s.contains(": ") && !s.endsWith(":") &&
            s.first() !in "'\"{}[]&*!|>%@`-?:," &&
            s.lowercase() !in reserved
        if (plainOk) return s
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    // ---- minimal block-mapping reader ----

    private fun parseTree(text: String): Map<String, Any> {
        val root = LinkedHashMap<String, Any>()
        val stack = ArrayDeque<Pair<Int, LinkedHashMap<String, Any>>>()
        stack.addLast(-1 to root)
        for (raw in text.lines()) {
            val line = stripComment(raw.replace("\t", "    ")).trimEnd()
            if (line.isBlank() || line.startsWith("---") || line.startsWith("%")) continue
            val indent = line.indexOfFirst { it != ' ' }
            val content = line.trim()
            if (content.startsWith("- ")) continue
            val colon = keyColon(content) ?: continue
            val key = unquote(content.substring(0, colon).trim())
            val value = content.substring(colon + 1).trim()
            while (stack.size > 1 && stack.last().first >= indent) stack.removeLast()
            val parent = stack.last().second
            if (value.isEmpty()) {
                val child = LinkedHashMap<String, Any>()
                parent[key] = child
                stack.addLast(indent to child)
            } else {
                parent[key] = unquote(value)
            }
        }
        return root
    }

    /** Removes a trailing comment; quotes only count when they start a token. */
    private fun stripComment(line: String): String {
        var quote: Char? = null
        for (i in line.indices) {
            val ch = line[i]
            if (quote != null) {
                if (ch == quote && (quote == '\'' || line[i - 1] != '\\')) quote = null
            } else if ((ch == '\'' || ch == '"') && (i == 0 || line[i - 1] == ' ')) {
                quote = ch
            } else if (ch == '#' && (i == 0 || line[i - 1].isWhitespace())) {
                return line.substring(0, i)
            }
        }
        return line
    }

    /** Index of the colon that separates key and value (a ':' followed by a space or end of line). */
    private fun keyColon(content: String): Int? {
        var quote: Char? = null
        for (i in content.indices) {
            val ch = content[i]
            if (quote != null) {
                if (ch == quote) quote = null
            } else if ((ch == '\'' || ch == '"') && (i == 0 || content[i - 1] == ' ')) {
                quote = ch
            } else if (ch == ':' && (i == content.lastIndex || content[i + 1] == ' ')) {
                return i
            }
        }
        return null
    }

    private fun unquote(s: String): String {
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') {
            val inner = s.substring(1, s.length - 1)
            val sb = StringBuilder()
            var i = 0
            while (i < inner.length) {
                val ch = inner[i]
                if (ch == '\\' && i + 1 < inner.length) {
                    when (val n = inner[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        else -> sb.append(n)
                    }
                    i += 2
                } else {
                    sb.append(ch)
                    i++
                }
            }
            return sb.toString()
        }
        if (s.length >= 2 && s.first() == '\'' && s.last() == '\'') {
            return s.substring(1, s.length - 1).replace("''", "'")
        }
        return s
    }
}
