package com.composepreviewpro.plugin.inspector

/**
 * Pretty-print a Kotlin builder chain so the Parameters panel doesn't render
 * it as a single mashed-up line.
 *
 * Strategy: normalise whitespace, then insert a newline + 4-space indent
 * before every TOP-LEVEL `.identifier(` segment. Nested dots — e.g.
 * `padding(start = 4.dp)` — stay on the same line because we track
 * parenthesis depth and only break when depth == 0.
 *
 *   Input  : `Modifier.size(10.dp).clip(CircleShape).background(Red)`
 *   Output : Modifier
 *                .size(10.dp)
 *                .clip(CircleShape)
 *                .background(Red)
 *
 * If the input has no chain (no top-level `.` followed by a letter), it is
 * returned with whitespace normalised but otherwise intact.
 *
 * Extracted from [com.composepreviewpro.plugin.toolwindow.ParametersPanel]
 * so it can be unit-tested without an IntelliJ test fixture.
 */
internal fun formatBuilderChain(raw: String): String {
    // Pass 1 — collapse all whitespace runs to a single space, then strip
    // whitespace around `.` whenever the dot is followed by an
    // identifier. This kills both leading and trailing junk around chain
    // boundaries (`Modifier .size(...)` → `Modifier.size(...)`) and
    // around member access (`MaterialTheme . colorScheme . background` →
    // `MaterialTheme.colorScheme.background`).
    val src = raw
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s*\\.\\s*(?=[A-Za-z_])"), ".")
        .trim()

    // Pass 2 — walk the cleaned text and insert a newline + 4-space
    // indent before every TOP-LEVEL `.identifier(` segment. Nested dots
    // inside parens / brackets / braces (e.g. the `.dp` in
    // `padding(start = 4.dp)`) stay put because we track depth.
    val out = StringBuilder(src.length + 16)
    var depth = 0
    var seenNonWhitespace = false
    var i = 0
    while (i < src.length) {
        val ch = src[i]
        when (ch) {
            '(', '[', '{' -> { out.append(ch); depth++ }
            ')', ']', '}' -> { out.append(ch); depth-- }
            '.' -> {
                // Only break on `.lowercaseIdent(` — i.e. an actual method
                // CALL in a builder chain. Property and enum access stay
                // on the same line because the visual goal is to flatten
                // *method chains*, not to shred dotted identifiers:
                //   • `Color.Red`                  → unchanged (uppercase)
                //   • `MaterialTheme.colorScheme`  → unchanged (no parens)
                //   • `Modifier.size(10.dp)`       → break on `.size(`
                val isChainBoundary = depth == 0 &&
                    seenNonWhitespace &&
                    isMethodCallAfterDot(src, i)
                if (isChainBoundary) {
                    out.append('\n').append("    ").append(ch)
                } else {
                    out.append(ch)
                }
            }
            else -> out.append(ch)
        }
        if (!ch.isWhitespace()) seenNonWhitespace = true
        i++
    }
    return out.toString()
}

/**
 * `true` iff the character following [dotIdx] starts a lowercase identifier
 * that is *immediately* called (i.e. is followed by `(` after the
 * identifier). This is the signature of a method invocation in a builder
 * chain. Returns `false` for enum/property access (`Color.Red`,
 * `MaterialTheme.colorScheme.background`).
 */
private fun isMethodCallAfterDot(src: String, dotIdx: Int): Boolean {
    var j = dotIdx + 1
    if (j >= src.length) return false
    val first = src[j]
    if (!first.isLetter() || !first.isLowerCase()) return false
    while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_')) j++
    // Skip any whitespace between the identifier and the call bracket —
    // `.let { ... }` with a space between `let` and `{` is still one
    // method-call. Without this skip, only `.let(...)` would be treated
    // as a chain boundary, breaking idiomatic Kotlin lambda-call syntax.
    while (j < src.length && src[j].isWhitespace()) j++
    // Accept either `(args)` or `{ lambda }` — Kotlin allows omitting
    // parentheses when the only argument is a lambda. `.let { ... }` is
    // semantically a method call just like `.let(block)`.
    return j < src.length && (src[j] == '(' || src[j] == '{')
}
