package com.impeesa.finance.core.model

/**
 * An amount of money in paise. Never use floating point for money.
 */
@JvmInline
value class Money(val paise: Long) : Comparable<Money> {
    operator fun plus(other: Money) = Money(paise + other.paise)
    operator fun minus(other: Money) = Money(paise - other.paise)
    operator fun unaryMinus() = Money(-paise)

    val isNegative: Boolean get() = paise < 0
    val isZero: Boolean get() = paise == 0L

    fun abs() = Money(kotlin.math.abs(paise))

    override fun compareTo(other: Money) = paise.compareTo(other.paise)

    companion object {
        val ZERO = Money(0)
        fun ofRupees(rupees: Long) = Money(rupees * 100)
    }
}
