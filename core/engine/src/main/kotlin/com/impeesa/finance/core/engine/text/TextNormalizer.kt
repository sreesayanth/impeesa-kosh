package com.impeesa.finance.core.engine.text

/** Cleans message text so the rules below see one consistent shape. */
object TextNormalizer {
    private val whitespace = Regex("\\s+")

    fun normalize(body: String): String =
        body
            .replace(' ', ' ')
            .replace(' ', ' ')
            .replace(whitespace, " ")
            .trim()
}
