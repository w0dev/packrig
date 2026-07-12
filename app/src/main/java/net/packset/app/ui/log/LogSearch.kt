package net.packset.app.ui.log

import net.packset.data.model.QsoContact

/**
 * Case-insensitive substring filter of [contacts] by DX call sign.
 * The query is trimmed first; a blank query returns the full list.
 */
fun filterByCall(contacts: List<QsoContact>, query: String): List<QsoContact> {
    val q = query.trim()
    if (q.isEmpty()) return contacts
    return contacts.filter { it.dxCall.contains(q, ignoreCase = true) }
}
