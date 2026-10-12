package com.impeesa.finance.core.engine.account

import com.impeesa.finance.core.engine.AccountMatch
import com.impeesa.finance.core.engine.KnownAccount

/** Matches a message to one of the user's accounts (IMPLEMENTATION_PLAN.md §6.8). */
object AccountResolver {
    /**
     * [sameBank] holds every sender code of the sender's institution: banks text from more than one
     * header (HDFCBK, HDFCBN), and all of them belong to the same accounts.
     */
    fun resolve(
        senderEntity: String?,
        mask: String?,
        accounts: List<KnownAccount>,
        sameBank: Set<String> = setOfNotNull(senderEntity),
    ): AccountMatch {
        fun KnownAccount.fromThisBank() = senderCodes.any { it in sameBank }
        if (mask != null) {
            val byMask = accounts.filter { it.mask != null && masksMatch(it.mask, mask) }
            byMask.firstOrNull { senderEntity != null && senderEntity in it.senderCodes }?.let { return AccountMatch.Matched(it.id) }
            byMask.singleOrNull { it.fromThisBank() }?.let { return AccountMatch.Matched(it.id) }
            if (byMask.size == 1) return AccountMatch.Matched(byMask.single().id)
        }
        if (senderEntity != null) {
            val bySender = accounts.filter { it.fromThisBank() }
            if (bySender.size == 1 && (mask == null || bySender.single().mask == null)) return AccountMatch.Matched(bySender.single().id)
        }
        return if (mask != null) AccountMatch.Suggested(senderEntity, mask) else AccountMatch.Unknown
    }

    /** Masks may show 3–6 digits; compare by suffix. */
    fun masksMatch(a: String, b: String): Boolean = a.endsWith(b) || b.endsWith(a)
}
