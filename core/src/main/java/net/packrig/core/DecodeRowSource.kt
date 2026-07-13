package net.packrig.core

/** Source of a row in the decode list. */
sealed interface DecodeRowSource {
    object Rx : DecodeRowSource
    object Tx : DecodeRowSource
}
