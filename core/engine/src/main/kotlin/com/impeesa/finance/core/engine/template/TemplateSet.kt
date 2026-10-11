package com.impeesa.finance.core.engine.template

import com.impeesa.finance.core.engine.amount.AmountExtractor
import com.impeesa.finance.core.engine.classify.MessageType
import com.impeesa.finance.core.engine.registry.EngineJson
import com.impeesa.finance.core.engine.registry.readResource
import com.impeesa.finance.core.model.Channel
import com.impeesa.finance.core.model.Direction
import kotlinx.serialization.Serializable

@Serializable
data class TemplateSpec(
    val id: String,
    /** SMS entity codes or notification package names this template applies to. */
    val senders: List<String>,
    val type: MessageType = MessageType.TRANSACTION,
    val direction: Direction,
    val channel: Channel = Channel.OTHER,
    /** Java regex with named groups: amount, mask, merchant, date, ref, balance, limit. */
    val pattern: String,
)

@Serializable
private data class TemplateFile(val version: Int, val templates: List<TemplateSpec>)

/** Fields a template pulled out of a message. */
data class TemplateMatch(
    val spec: TemplateSpec,
    val amount: Long?,
    val mask: String?,
    val merchant: String?,
    val date: String?,
    val reference: String?,
    val balance: Long?,
    val limit: Long?,
)

/**
 * Bank-specific message formats, shipped as data (IMPLEMENTATION_PLAN.md §6.6). A template match
 * raises confidence and overrides the generic extractors.
 */
class TemplateSet private constructor(val version: Int, specs: List<TemplateSpec>) {
    private val compiled = specs.map { it to Regex(it.pattern, RegexOption.IGNORE_CASE) }
    private val bySender = compiled.flatMap { entry -> entry.first.senders.map { it.uppercase() to entry } }
        .groupBy({ it.first }, { it.second })

    val size: Int get() = compiled.size

    fun match(senderKey: String?, text: String): TemplateMatch? {
        val candidates = senderKey?.let { bySender[it.uppercase()] } ?: return null
        for ((spec, regex) in candidates) {
            val m = regex.find(text) ?: continue
            fun group(name: String): String? = runCatching { m.groups[name]?.value }.getOrNull()?.trim()
            return TemplateMatch(
                spec = spec,
                amount = group("amount")?.let(AmountExtractor::parseMinorUnits),
                mask = group("mask")?.takeLast(4),
                merchant = group("merchant"),
                date = group("date"),
                reference = group("ref"),
                balance = group("balance")?.let(AmountExtractor::parseMinorUnits),
                limit = group("limit")?.let(AmountExtractor::parseMinorUnits),
            )
        }
        return null
    }

    companion object {
        fun load(): TemplateSet {
            val file = EngineJson.decodeFromString(TemplateFile.serializer(), readResource("/impeesa/templates.json"))
            return TemplateSet(file.version, file.templates)
        }

        fun of(specs: List<TemplateSpec>) = TemplateSet(0, specs)
    }
}
