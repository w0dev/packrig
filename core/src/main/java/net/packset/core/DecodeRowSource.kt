package net.packset.core

/** Source of a row in the decode list. */
sealed interface DecodeRowSource {
    object Rx : DecodeRowSource
    object Tx : DecodeRowSource
}
