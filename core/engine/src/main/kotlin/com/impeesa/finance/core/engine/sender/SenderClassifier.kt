package com.impeesa.finance.core.engine.sender

enum class SenderKind { COMMERCIAL, PERSONAL, UNKNOWN }

/** TRAI category suffix appended to commercial headers, when present. */
enum class HeaderCategory { PROMOTIONAL, SERVICE, TRANSACTIONAL, GOVERNMENT }

data class SenderInfo(
    val kind: SenderKind,
    val entity: String? = null,
    val category: HeaderCategory? = null,
)

/**
 * Reads an SMS sender address. Commercial headers look like "VM-HDFCBK" or "AD-HDFCBK-S";
 * personal numbers are dropped by callers before the body is looked at.
 */
object SenderClassifier {
    private val personal = Regex("^\\+?\\d{10,13}$")
    private val commercial = Regex("^(?:[A-Z]{2}-)?([A-Z0-9]{6})(?:-([PSTG]))?$")

    fun classify(address: String): SenderInfo {
        val value = address.trim().uppercase().replace(" ", "")
        if (personal.matches(value)) return SenderInfo(SenderKind.PERSONAL)
        val match = commercial.matchEntire(value) ?: return SenderInfo(SenderKind.UNKNOWN)
        val entity = match.groupValues[1]
        if (entity.all(Char::isDigit)) return SenderInfo(SenderKind.UNKNOWN)
        val category = when (match.groupValues[2]) {
            "P" -> HeaderCategory.PROMOTIONAL
            "S" -> HeaderCategory.SERVICE
            "T" -> HeaderCategory.TRANSACTIONAL
            "G" -> HeaderCategory.GOVERNMENT
            else -> null
        }
        return SenderInfo(SenderKind.COMMERCIAL, entity, category)
    }
}
