package net.ft8vc.app

import androidx.compose.material3.SnackbarDuration

/** One-shot notification from the ViewModel to the operate UI. */
data class SnackbarEvent(
    val text: String,
    val tag: Tag = Tag.TRANSIENT,
) {
    enum class Tag(val duration: SnackbarDuration) {
        /** Brief confirmation; auto-dismisses on the short timer. */
        TRANSIENT(SnackbarDuration.Short),
        /** Logged QSO summary; lingers slightly longer than transient events. */
        QSO_COMPLETE(SnackbarDuration.Long),
        /** Operator-visible failure; lingers until dismissed. */
        ERROR(SnackbarDuration.Long),
    }
}
