package net.ft8vc.app

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Formats a UTC epoch-millis instant as the decode-list timestamp string `HHmmss`.
 *
 * Matches the format RX decodes use (see DecodeController's `HHmmss`/UTC formatter)
 * so the synthetic self-TX row is visually identical to received rows.
 */
private val ROW_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HHmmss").withZone(ZoneOffset.UTC)

fun formatRowTimeUtc(utcMillis: Long): String =
    ROW_TIME_FORMAT.format(Instant.ofEpochMilli(utcMillis))
