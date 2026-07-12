package net.packset.app.ui.operate

import net.packset.core.TxSlotParity

/**
 * Neutral-grey tint alpha for the decode list's UTC cell, keyed to slot parity.
 * EVEN slots are tinted; ODD slots get no tint. Kept low so it never competes
 * with the semantic category fills.
 */
const val SLOT_TINT_ALPHA: Float = 0.07f

/** Tint alpha for [parity]: [SLOT_TINT_ALPHA] on EVEN, 0f (transparent) on ODD. */
fun slotTintAlpha(parity: TxSlotParity): Float =
    if (parity == TxSlotParity.EVEN) SLOT_TINT_ALPHA else 0f
