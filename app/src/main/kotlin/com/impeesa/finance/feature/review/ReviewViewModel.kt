package com.impeesa.finance.feature.review

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.impeesa.finance.core.data.CategoryRepository
import com.impeesa.finance.core.data.ReviewRepository
import com.impeesa.finance.core.data.db.ReviewRow
import com.impeesa.finance.core.model.Category
import com.impeesa.finance.core.model.CategoryKind
import com.impeesa.finance.core.model.DateLabels
import com.impeesa.finance.core.model.Direction
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.ui.UndoBus
import com.impeesa.finance.ui.UndoEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

@Immutable
data class ReviewCard(
    val id: Long,
    val title: String,
    val amount: Money,
    val currency: String,
    val isCredit: Boolean,
    val sourceLabel: String,
    val whenLabel: String,
    val accountLabel: String,
    val reasonLabel: String,
    val message: String?,
    val categories: List<Category>,
    val selectedCategoryId: Long?,
    /** Set when this may be the same payment as an earlier capture. */
    val duplicateOf: Long? = null,
)

@Immutable
data class ReviewUiState(
    val loading: Boolean = true,
    val current: ReviewCard? = null,
    val upNext: List<ReviewCard> = emptyList(),
    val total: Int = 0,
)

class ReviewViewModel(
    private val review: ReviewRepository,
    categories: CategoryRepository,
    private val undo: UndoBus,
    private val clock: Clock,
) : ViewModel() {

    /** Category the user picked for an item, before confirming. */
    private val picks = MutableStateFlow<Map<Long, Long>>(emptyMap())

    val state: StateFlow<ReviewUiState> = combine(
        review.observeQueue(),
        categories.observe(CategoryKind.EXPENSE),
        categories.observe(CategoryKind.INCOME),
        picks,
    ) { rows, expense, income, chosen ->
        val today = LocalDate.now(clock)
        val cards = rows.map { row -> row.toCard(if (row.direction == Direction.CREDIT) income else expense, chosen[row.id], today) }
        ReviewUiState(loading = false, current = cards.firstOrNull(), upNext = cards.drop(1), total = cards.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState())

    fun pickCategory(transactionId: Long, categoryId: Long) = picks.update { it + (transactionId to categoryId) }

    fun confirm(card: ReviewCard) {
        viewModelScope.launch {
            review.confirm(card.id, card.selectedCategoryId)
            undo.post(UndoEvent("Added ${card.title}") { review.backToReview(listOf(card.id)) })
        }
    }

    fun ignore(card: ReviewCard) {
        viewModelScope.launch {
            review.ignore(card.id)
            undo.post(UndoEvent("Marked as not spending") { review.backToReview(listOf(card.id)) })
        }
    }

    fun mergeDuplicate(card: ReviewCard) {
        viewModelScope.launch {
            val target = review.mergeDuplicate(card.id) ?: return@launch
            undo.post(UndoEvent("Merged into one payment") { review.restoreDuplicate(card.id, target) })
        }
    }

    fun keepBoth(card: ReviewCard) {
        viewModelScope.launch {
            review.keepBoth(card.id)
            undo.post(UndoEvent("Kept as a separate payment") { review.backToReview(listOf(card.id)) })
        }
    }

    fun confirmAll() {
        viewModelScope.launch {
            val ids = review.confirmAll()
            if (ids.isNotEmpty()) undo.post(UndoEvent("Added ${ids.size} transactions") { review.backToReview(ids) })
        }
    }

    private fun ReviewRow.toCard(categories: List<Category>, picked: Long?, today: LocalDate): ReviewCard {
        val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(occurredAt), clock.zone)
        val sender = senderCode ?: rawSender
        return ReviewCard(
            id = id,
            title = counterparty ?: if (direction == Direction.CREDIT) "Money received" else "Payment",
            amount = Money(amountPaise),
            currency = currency,
            isCredit = direction == Direction.CREDIT,
            sourceLabel = listOfNotNull("SMS", sender).joinToString(" · "),
            whenLabel = DateLabels.chip(time.toLocalDate(), today) + ", " + DateLabels.time(time.toLocalTime()),
            accountLabel = accountName ?: accountMask?.let { "Account ••$it (not added yet)" } ?: "Account not known",
            duplicateOf = duplicateOf,
            reasonLabel = when (reviewReason) {
                "POSSIBLE_DUPLICATE" -> "Possible duplicate of " + (duplicateCounterparty ?: "a payment") +
                    " from " + (if (duplicateSource == "SMS") "a bank SMS" else "an app notification") + " a few minutes apart"
                "FOREIGN_CURRENCY" -> "Foreign currency — check the amount in rupees"
                "FAILED_BUT_DEBITED" -> "Debited, but the bank says it failed"
                "UNKNOWN_SENDER" -> "From a sender we don't know yet"
                else -> "We weren't sure — please check"
            },
            message = rawBody,
            categories = categories,
            selectedCategoryId = picked ?: categoryId ?: categories.firstOrNull()?.id.takeIf { direction != Direction.CREDIT },
        )
    }
}
