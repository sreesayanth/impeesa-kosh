package com.impeesa.finance.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A message with an Undo action, shown by the root snackbar host. */
class UndoEvent(val message: String, val undo: suspend () -> Unit)

/** Lets any screen ask for an "Undo" snackbar, even one that is closing. */
class UndoBus {
    private val events = MutableSharedFlow<UndoEvent>(extraBufferCapacity = 4)
    val flow: SharedFlow<UndoEvent> = events.asSharedFlow()

    fun post(event: UndoEvent) {
        events.tryEmit(event)
    }
}
