package com.impeesa.finance.feature.profile

/** The user's on-device profile. Never leaves the phone. */
data class Profile(val name: String = "", val colorIndex: Int = 0) {
    val hasName: Boolean get() = name.isNotBlank()

    /** "Aarav Kumar" -> "AK", "aarav" -> "A", blank -> "". */
    val initials: String
        get() = name.trim().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }

    /** First word only, for greetings ("Good evening, Aarav"). */
    val firstName: String get() = name.trim().substringBefore(' ')

    companion object {
        const val MAX_NAME = 30

        /** Trims, collapses runs of spaces and caps the length of a typed name. */
        fun cleanName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).trim()
    }
}
