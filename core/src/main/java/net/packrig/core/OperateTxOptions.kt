package net.packrig.core

/**
 * Operate-screen TX dropdown: idle shows free-text only; active QSO shows standard steps + free text.
 */
object OperateTxOptions {

    const val CUSTOM_LABEL = "Free text…"

    val qsoSequenceSteps: List<QsoTxStep> = listOf(
        QsoTxStep.Cq,
        QsoTxStep.Grid,
        QsoTxStep.Report,
        QsoTxStep.RReport,
        QsoTxStep.Roger,
        QsoTxStep.SeventyThree,
    )

    data class MenuEntry(
        val step: QsoTxStep,
        val label: String,
        /** Composed FT8 line preview, null for free-text entry. */
        val preview: String?,
    )

    /** Dropdown rows for the current operating context. */
    fun menuEntries(
        qsoActive: Boolean,
        myCall: String,
        myGrid: String,
        cqModifier: String?,
        form: QsoForm,
    ): List<MenuEntry> {
        if (!qsoActive) {
            return listOf(MenuEntry(QsoTxStep.Custom, CUSTOM_LABEL, preview = null))
        }
        val sequence = qsoSequenceSteps.map { step ->
            MenuEntry(
                step = step,
                label = step.label,
                preview = QsoFormLogic.compose(myCall, myGrid, cqModifier, form.copy(txStep = step)),
            )
        }
        return sequence + MenuEntry(QsoTxStep.Custom, CUSTOM_LABEL, preview = null)
    }

    fun menuLabel(entry: MenuEntry): String = entry.label
}
