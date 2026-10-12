package com.impeesa.finance.core.engine.registry

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal val EngineJson = Json { ignoreUnknownKeys = true }

internal fun readResource(path: String): String =
    requireNotNull(Registries::class.java.getResourceAsStream(path)) { "Missing engine resource $path" }
        .bufferedReader()
        .use { it.readText() }

private object Registries

enum class InstitutionKind { BANK, CARD, WALLET, PAYMENTS_BANK, NBFC }

@Serializable
data class Institution(val code: String, val institution: String, val kind: InstitutionKind)

/** SMS header entity codes of banks, card issuers and wallets (IMPLEMENTATION_PLAN.md Appendix C). */
class SenderRegistry(institutions: List<Institution>) {
    private val byCode = institutions.associateBy { it.code.uppercase() }
    private val byName = institutions.groupBy { it.institution.lowercase() }

    fun find(entity: String?): Institution? = entity?.let { byCode[it.uppercase()] }

    /**
     * Every sender code of the same institution as [entity] (HDFCBK and HDFCBN are both HDFC Bank),
     * including [entity] itself; just [entity] when it isn't a known institution.
     */
    fun codesOf(entity: String): Set<String> {
        val institution = find(entity) ?: return setOf(entity)
        return byName[institution.institution.lowercase()].orEmpty().map { it.code.uppercase() }.toSet() + entity
    }

    /** For RCS/messaging-app notifications, whose title is the sender's display name. */
    fun findByName(name: String?): Institution? {
        val key = name?.trim()?.lowercase() ?: return null
        return byName[key]?.firstOrNull()
            ?: byName.entries.firstOrNull { (institution, _) -> key.startsWith(institution) || institution.startsWith(key) }?.value?.firstOrNull()
    }

    companion object {
        fun load(): SenderRegistry =
            SenderRegistry(EngineJson.decodeFromString(ListSerializer(Institution.serializer()), readResource("/impeesa/senders.json")))
    }
}

enum class AppKind { UPI, WALLET, CARD_PAYMENTS, SHOPPING, BANK, MESSAGING }

@Serializable
data class PaymentApp(val packageName: String, val app: String, val kind: AppKind)

/** Apps whose notifications may carry payments (IMPLEMENTATION_PLAN.md Appendix B). */
class AppRegistry(apps: List<PaymentApp>) {
    private val byPackage = apps.associateBy { it.packageName }

    fun find(packageName: String): PaymentApp? = byPackage[packageName]

    val all: Collection<PaymentApp> get() = byPackage.values

    companion object {
        fun load(): AppRegistry =
            AppRegistry(EngineJson.decodeFromString(ListSerializer(PaymentApp.serializer()), readResource("/impeesa/apps.json")))
    }
}
