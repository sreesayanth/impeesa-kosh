package com.impeesa.finance.core.model

/** "1 account", "3 accounts". Pass [plural] when adding an "s" is wrong. */
fun counted(count: Int, noun: String, plural: String = noun + "s"): String =
    "$count ${if (count == 1) noun else plural}"
