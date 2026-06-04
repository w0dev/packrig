package net.ft8vc.rig

/**
 * Pure builder/parser for the subset of the Yaesu FT-891 CAT protocol used by
 * FT8VC: VFO-A frequency and operating mode.
 *
 * CAT commands are ASCII, terminated by `;`. A query is the bare command with no
 * argument (e.g. `FA;`), and the radio answers with the same opcode plus its
 * data (e.g. `FA014074000;`). Frequencies are expressed in whole Hz, zero-padded
 * to 9 digits. This object only formats and parses strings; the serial transport
 * lives in [DigirigRigBackend].
 */
object Ft891Cat {

    const val TERMINATOR = ';'

    /** FT-891 operating modes and their `MD0x` opcodes. FT8 runs on [DATA_USB]. */
    enum class Mode(val code: Char, val label: String) {
        LSB('1', "LSB"),
        USB('2', "USB"),
        CW_U('3', "CW-U"),
        FM('4', "FM"),
        AM('5', "AM"),
        RTTY_L('6', "RTTY-L"),
        CW_L('7', "CW-L"),
        DATA_L('8', "DATA-L"),
        RTTY_U('9', "RTTY-U"),
        DATA_FM('A', "DATA-FM"),
        FM_N('B', "FM-N"),
        DATA_USB('C', "DATA-U"),
        AM_N('D', "AM-N"),
        C4FM('E', "C4FM");

        companion object {
            fun fromCode(code: Char): Mode? = entries.firstOrNull { it.code == code }
        }
    }

    /** Lowest/highest VFO frequencies the FT-891 will accept, in Hz. */
    const val MIN_FREQ_HZ = 30_000L
    const val MAX_FREQ_HZ = 56_000_000L

    /** Query VFO-A frequency. The radio replies with [parseFrequencyResponse]. */
    fun readFrequencyCommand(): String = "FA;"

    /** Set VFO-A to [hz] (whole Hz). Throws if outside the FT-891 tuning range. */
    fun setFrequencyCommand(hz: Long): String {
        require(hz in MIN_FREQ_HZ..MAX_FREQ_HZ) { "Frequency out of range: $hz Hz" }
        return "FA%09d;".format(hz)
    }

    /** Parse an `FAnnnnnnnnn;` reply into Hz, or null if it is malformed. */
    fun parseFrequencyResponse(response: String): Long? {
        val body = opcodeBody(response, "FA") ?: return null
        if (body.length != 9 || !body.all { it.isDigit() }) return null
        return body.toLongOrNull()
    }

    /**
     * Key the transmitter over CAT (`TX1;`). On the FT-891 the CAT/serial jack
     * has no hardware PTT line, so software PTT is done with this command rather
     * than the CP2102 RTS pin.
     */
    fun txOnCommand(): String = "TX1;"

    /** Un-key the transmitter over CAT (`TX0;`). */
    fun txOffCommand(): String = "TX0;"

    /** Query TX state. Reply is `TX0;` (RX), `TX1;` (CAT TX), or `TX2;` (other). */
    fun readTxCommand(): String = "TX;"

    /** Parse a `TXn;` reply into the transmit flag, or null if malformed. */
    fun parseTxResponse(response: String): Boolean? {
        val body = opcodeBody(response, "TX") ?: return null
        return when (body.firstOrNull()) {
            '0' -> false
            '1', '2' -> true
            else -> null
        }
    }

    /** Query the operating mode. The radio replies with [parseModeResponse]. */
    fun readModeCommand(): String = "MD0;"

    /** Set the operating mode (`MD0x;`). */
    fun setModeCommand(mode: Mode): String = "MD0${mode.code};"

    /** Parse an `MD0x;` reply into a [Mode], or null if unrecognized. */
    fun parseModeResponse(response: String): Mode? {
        val body = opcodeBody(response, "MD") ?: return null
        // Reply form is "0x" (VFO digit + mode code); a bare "x" is also tolerated.
        val code = when (body.length) {
            2 -> body[1]
            1 -> body[0]
            else -> return null
        }
        return Mode.fromCode(code)
    }

    /** Strip a trailing terminator and matching [opcode] prefix, returning the body. */
    private fun opcodeBody(response: String, opcode: String): String? {
        val trimmed = response.trim().removeSuffix(TERMINATOR.toString())
        if (!trimmed.startsWith(opcode)) return null
        return trimmed.substring(opcode.length)
    }
}
