package net.ft8vc.core

/**
 * Distinguishes which decode pass produced a [net.ft8vc.app.DecodeRow]:
 * - [Early]: partial-slot pass at slot-relative t=12.000s
 * - [Full]: full 15s slot pass at the slot boundary (the v1.0 pass)
 *
 * UI must NOT branch on this — early and full rows render pixel-identically.
 * Exists for telemetry, dedup bookkeeping, and tests.
 */
sealed interface DecodePassSource {
    object Early : DecodePassSource
    object Full : DecodePassSource
}
