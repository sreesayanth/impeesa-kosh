package com.impeesa.finance.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A short message for the root snackbar, with an Undo action when [undo] is set. */
class UndoEvent(val message: String, val undo: (suspend () -> Unit)? = null)

/** Lets any screen ask for a snackbar, even one that is closing. */
class UndoBus {
    private val events = MutableSharedFlow<UndoEvent>(extraBufferCapacity = 4)
    val flow: SharedFlow<UndoEvent> = events.asSharedFlow()

    fun post(event: UndoEvent) {
        events.tryEmit(event)
    }
}
