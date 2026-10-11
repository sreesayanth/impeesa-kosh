package com.impeesa.finance.ui

import androidx.compose.runtime.Immutable
import com.impeesa.finance.core.data.db.TransactionRow
import com.impeesa.finance.core.model.DateLabels
import com.impeesa.finance.core.model.Direction
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.TxnSource
import com.impeesa.finance.core.model.TxnType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** A transaction row, fully formatted for display. */
@Immutable
data class TxnUi(
    val id: Long,
    val title: String,
    val subtitle: String,
    val amount: Money,
    val color: Int?,
    val isTransfer: Boolean,
    val localDate: Int,
)

fun TransactionRow.toUi(zone: ZoneId, today: LocalDate): TxnUi {
    val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(occurredAt), zone)
    val isTransfer = type == TxnType.TRANSFER
    val title = note ?: counterparty ?: categoryName ?: if (isTransfer) "Transfer" else "Transaction"
    val sourceLabel = when (source) {
        TxnSource.SMS -> "SMS"
        TxnSource.NOTIFICATION -> "Notification"
        else -> null
    }
    val parts = buildList {
        if (isTransfer) add("From ${accountName ?: "account"}")
        else {
            if (categoryName != null && categoryName != title) add(categoryName)
            accountName?.let(::add)
        }
        sourceLabel?.let(::add)
        add(DateLabels.whenShort(time.toLocalDate(), time.toLocalTime(), today))
    }
    return TxnUi(
        id = id,
        title = title,
        subtitle = parts.joinToString(" · "),
        amount = Money(if (direction == Direction.DEBIT) -amountPaise else amountPaise),
        color = categoryColor,
        isTransfer = isTransfer,
        localDate = localDate,
    )
}
