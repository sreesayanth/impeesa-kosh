package com.impeesa.finance.core.model

enum class Direction { DEBIT, CREDIT }

enum class TxnType { EXPENSE, INCOME, TRANSFER, REFUND, ADJUSTMENT }

enum class TxnStatus { CONFIRMED, PENDING_REVIEW, IGNORED }

enum class TxnSource { SMS, NOTIFICATION, MANUAL, IMPORT }

enum class AccountType { BANK, CARD, CASH, WALLET }

enum class Channel { UPI, CARD, NETBANKING, ATM, AUTOPAY, CASH, OTHER }

enum class CategoryKind { EXPENSE, INCOME, TRANSFER }
