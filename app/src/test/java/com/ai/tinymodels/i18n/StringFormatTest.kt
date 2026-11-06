package com.ai.tinymodels.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every resource format string must be called with as many arguments as it
 * declares placeholders. MissingFormatArgumentException crashes the app on
 * the frame that renders it — the home screen crashed for a user when
 * `%2$d` was passed without its `%1$s`. No way to catch that in a running
 * build, so the whole app is scanned here.
 *
 * Mirrors the manual audit:
 *  - `stringResource` / `pluralStringResource` / `getString` calls are
 *    located by brace-balanced scanning (strings and char literals included),
 *  - the `R.string` / `R.plurals` argument position is found, non-resource
 *    arguments on each side are counted,
 *  - for `pluralStringResource` the quantity argument is dropped before
 *    comparing (it selects the plural, it is not a format argument),
 *  - argument spreads (`*args`) are skipped.
 */
class StringFormatTest {

    private val valuesDir = File("src/main/res/values")
    private val javaDir = File("src/main/java")

    private data class Expectation(val isPlural: Boolean, val argCount: Int)

    /** name -> placeholder expectation, keyed per resource name. */
    private fun expectedArgs(): Map<String, Expectation> {
        val map = LinkedHashMap<String, Expectation>()
        fun indicesOf(text: String): List<Int> = Regex("""%(\d+)\$(?:[0-9.,#]+)?[a-zA-Z]""")
            .findAll(text)
            .map { it.groupValues[1].toInt() }
            .toList()
        fun bareCount(text: String): Int = Regex("""%(?![0-9%])[0-9.,#a-zA-Z]*[a-zA-Z]""")
            .findAll(text.replace("%%", ""))
            .count()

        for (file in valuesDir.listFiles()?.filter { it.name.endsWith(".xml") }.orEmpty()) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val strings = doc.getElementsByTagName("string")
            for (i in 0 until strings.length) {
                val el = strings.item(i)
                val name = el.attributes.getNamedItem("name").nodeValue
                val text = el.textContent
                val explicit = indicesOf(text)
                val expected = if (explicit.isNotEmpty()) {
                    explicit.toSet().size
                } else {
                    bareCount(text)
                }
                map[name] = Expectation(isPlural = false, argCount = expected)
            }
            val plurals = doc.getElementsByTagName("plurals")
            for (i in 0 until plurals.length) {
                val el = plurals.item(i)
                val name = el.attributes.getNamedItem("name").nodeValue
                val items = el.childNodes
                var max = 0
                for (j in 0 until items.length) {
                    val item = items.item(j)
                    val text = item.textContent
                    val explicit = indicesOf(text)
                    val count = if (explicit.isNotEmpty()) explicit.toSet().size else bareCount(text)
                    if (count > max) max = count
                }
                map[name] = Expectation(isPlural = true, argCount = max)
            }
        }
        return map
    }

    /** Splits top-level commas; respects nested parens, strings, chars and escapes. */
    private fun splitTopLevel(args: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        val cur = StringBuilder()
        var inString = false
        var inChar = false
        var escape = false
        fun close() {
            val s = cur.toString().trim()
            if (s.isNotEmpty()) out.add(s)
            cur.clear()
        }
        for (c in args) {
            if (escape) {
                cur.append(c)
                escape = false
                continue
            }
            when {
                c == '\\' -> {
                    cur.append(c)
                    escape = true
                }
                inString -> {
                    cur.append(c)
                    if (c == '"') inString = false
                }
                inChar -> {
                    cur.append(c)
                    if (c == '\'') inChar = false
                }
                c == '"' -> {
                    cur.append(c)
                    inString = true
                }
                c == '\'' -> {
                    cur.append(c)
                    inChar = true
                }
                c == '(' || c == '[' || c == '{' -> {
                    depth++
                    cur.append(c)
                }
                c == ')' || c == ']' || c == '}' -> {
                    depth--
                    cur.append(c)
                }
                c == ',' && depth == 0 -> close()
                else -> cur.append(c)
            }
        }
        close()
        return out
    }

    private val callPattern = Regex("""(stringResource|pluralStringResource|quantityStringResource|getString)\s*\(""")

    @Test
    fun `every resource format call passes the right number of arguments`() {
        val expectations = expectedArgs()
        val problems = mutableListOf<String>()

        for (file in javaDir.walkTopDown().filter { it.extension == "kt" }) {
            val text = file.readText()
            for (match in callPattern.findAll(text)) {
                val start = match.range.last + 1
                var depth = 1
                var i = start
                var inString = false
                var inChar = false
                var escape = false
                while (i < text.length && depth > 0) {
                    val c = text[i]
                    when {
                        escape -> escape = false
                        c == '\\' -> escape = true
                        inString -> if (c == '"') inString = false
                        inChar -> if (c == '\'') inChar = false
                        c == '"' -> inString = true
                        c == '\'' -> inChar = true
                        c == '(' -> depth++
                        c == ')' -> depth--
                    }
                    i++
                }
                val argsRaw = text.substring(start, i - 1)
                val args = splitTopLevel(argsRaw)
                val idPattern = Regex("""(?:[\w.]*)R\.(string|plurals)\.(\w+)""")
                val idIndex = args.indexOfFirst { idPattern.matches(it.trim()) }
                if (idIndex < 0) continue
                val kind = idPattern.matchEntire(args[idIndex])!!.groupValues[1]
                val name = idPattern.matchEntire(args[idIndex])!!.groupValues[2]
                val after = args.drop(idIndex + 1).let {
                    if (kind == "plurals" && it.isNotEmpty()) it.drop(1) else it
                }
                if (after.any { it.startsWith("*") }) continue
                val expected = expectations[name]
                if (expected == null) {
                    problems += "${file.path}: unknown resource '$name'"
                    continue
                }
                if (after.size != expected.argCount) {
                    problems += "${file.path}: $name called with ${after.size} args, resource expects ${expected.argCount}"
                }
            }
        }

        assertTrue(
            "Resource format-argument mismatches (app crashes at the offending frame):\n" +
                problems.joinToString("\n"),
            problems.isEmpty()
        )
    }
}
