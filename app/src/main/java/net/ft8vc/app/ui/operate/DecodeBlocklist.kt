package net.ft8vc.app.ui.operate

import net.ft8vc.core.CallBaseName
import net.ft8vc.core.DecodeRowSource
import net.ft8vc.core.QsoMessages

/** Blocklist visibility rules for decode rows. Our own TX rows are never blocked. */
object DecodeBlocklist {

    /** True when [message]'s sender base-call is in [blocked] (and the row is not our own TX). */
    fun isSenderBlocked(message: String, source: DecodeRowSource, blocked: Collection<String>): Boolean {
        if (source is DecodeRowSource.Tx) return false
        val base = QsoMessages.senderCall(message)?.let { CallBaseName.of(it) } ?: return false
        return base in blocked
    }

    /** Base-call to block from a long-press on this row, or null when there's nothing blockable. */
    fun senderToBlock(message: String, source: DecodeRowSource): String? {
        if (source is DecodeRowSource.Tx) return null
        return QsoMessages.senderCall(message)?.let { CallBaseName.of(it) }
    }
}
