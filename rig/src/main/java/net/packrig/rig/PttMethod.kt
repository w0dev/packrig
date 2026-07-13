package net.packrig.rig

/**
 * Default PTT keying a rig descriptor prescribes. Maps one-to-one onto the app's
 * `PttPreference`. AUTO = probe CAT, fall back to RTS (the FT-891/Digirig path).
 */
enum class PttMethod { AUTO, RTS, CAT }
