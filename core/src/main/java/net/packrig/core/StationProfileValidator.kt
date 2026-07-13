package net.packrig.core

/**
 * Validation for the operator station profile. Used to gate transmit and to
 * prompt the user when call/grid are missing or malformed.
 *
 * Callsign rules are intentionally loose so portable/special prefixes
 * (`W0DEV/P`, `KP4/AB1CD`) still pass — we only refuse obviously empty or
 * placeholder values. Grid rules defer to [MaidenheadGrid] for 4-char
 * locators; 6-char grids extend the 4-char check with two extra letters.
 */
object StationProfileValidator {

    private val CALLSIGN = Regex("^[A-Z0-9/]{3,10}$")
    private val GRID6_EXT = Regex("^[A-X]{2}$", RegexOption.IGNORE_CASE)

    fun isValidCall(call: String): Boolean {
        val c = call.trim().uppercase()
        if (!CALLSIGN.matches(c)) return false
        val core = c.substringBefore('/')
        return core.any { it.isDigit() } && core.any { it.isLetter() }
    }

    fun isValidGrid(grid: String): Boolean {
        val g = grid.trim()
        return when (g.length) {
            4 -> MaidenheadGrid.isValid4(g)
            6 -> MaidenheadGrid.isValid4(g.take(4)) && GRID6_EXT.matches(g.takeLast(2))
            else -> false
        }
    }

    fun isComplete(call: String, grid: String): Boolean =
        isValidCall(call) && isValidGrid(grid)
}
