package net.ft8vc.rig

/**
 * Yaesu "new CAT" ASCII protocol (FT-891 / FT-991A / FTDX10 / FT-710 family),
 * parameterized by a [YaesuModelSpec]. Commands are ASCII terminated by `;`;
 * a query is the bare opcode (`FA;`) and the radio answers with opcode + data
 * (`FA014074000;`). Frequencies are whole Hz zero-padded to 9 digits.
 */
class YaesuCat(val model: YaesuModelSpec) : CatProtocol {

    override val replyTerminator: Byte = TERMINATOR.code.toByte()

    override val dataModeLabel: String = model.modeLabels.getValue(model.dataModeCode)

    override fun readFrequencyCommand(): ByteArray = ascii("FA;")

    override fun setFrequencyCommand(hz: Long): ByteArray? {
        if (hz !in model.minFreqHz..model.maxFreqHz) return null
        return ascii("FA%09d;".format(hz))
    }

    override fun parseFrequency(reply: ByteArray): Long? {
        val body = opcodeBody(reply, "FA") ?: return null
        if (body.length != 9 || !body.all { it.isDigit() }) return null
        return body.toLongOrNull()
    }

    override fun readModeCommand(): ByteArray = ascii("MD0;")

    override fun parseModeLabel(reply: ByteArray): String? {
        val body = opcodeBody(reply, "MD") ?: return null
        // Reply form is "0x" (VFO digit + mode code); a bare "x" is tolerated.
        val code = when (body.length) {
            2 -> body[1]
            1 -> body[0]
            else -> return null
        }
        return model.modeLabels[code]
    }

    override fun setDataModeCommand(): ByteArray = ascii("MD0${model.dataModeCode};")

    override fun pttCommand(on: Boolean): ByteArray = ascii(if (on) "TX1;" else "TX0;")

    /** Strip whitespace, the trailing terminator, and a matching opcode prefix. */
    private fun opcodeBody(reply: ByteArray, opcode: String): String? {
        val trimmed = reply.toString(Charsets.US_ASCII).trim().removeSuffix(TERMINATOR.toString())
        if (!trimmed.startsWith(opcode)) return null
        return trimmed.substring(opcode.length)
    }

    private fun ascii(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    companion object {
        const val TERMINATOR = ';'

        /** Yaesu FT-891 — the reference rig. Mirrors the legacy Ft891Cat table. */
        val FT891 = YaesuModelSpec(
            name = "Yaesu FT-891",
            minFreqHz = 30_000L,
            maxFreqHz = 56_000_000L,
            dataModeCode = 'C',
            modeLabels = mapOf(
                '1' to "LSB", '2' to "USB", '3' to "CW-U", '4' to "FM",
                '5' to "AM", '6' to "RTTY-L", '7' to "CW-L", '8' to "DATA-L",
                '9' to "RTTY-U", 'A' to "DATA-FM", 'B' to "FM-N", 'C' to "DATA-U",
                'D' to "AM-N", 'E' to "C4FM",
            ),
        )
    }
}
