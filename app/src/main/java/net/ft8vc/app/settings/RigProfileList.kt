package net.ft8vc.app.settings

import net.ft8vc.rig.RigProfile

/** Pure list rules for saved rig profiles: cap of 5, unique names, deletion fallback. */
object RigProfileList {

    const val MAX = 5

    /** Save-blocking validation message for [name], or null when acceptable. */
    fun nameError(name: String, profiles: List<RigProfile>, selfId: String?): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "Name is required"
        val clash = profiles.any { it.id != selfId && it.name.equals(trimmed, ignoreCase = true) }
        return if (clash) "A rig with this name already exists" else null
    }

    /** Add or replace by id. Null when the cap or name rule rejects the save. */
    fun upsert(profiles: List<RigProfile>, profile: RigProfile): List<RigProfile>? {
        if (nameError(profile.name, profiles, profile.id) != null) return null
        val trimmed = profile.copy(name = profile.name.trim())
        val index = profiles.indexOfFirst { it.id == trimmed.id }
        return when {
            index >= 0 -> profiles.toMutableList().also { it[index] = trimmed }
            profiles.size >= MAX -> null
            else -> profiles + trimmed
        }
    }

    fun delete(profiles: List<RigProfile>, id: String): List<RigProfile> =
        profiles.filterNot { it.id == id }

    /** New selection after a delete: keep current if it survives, else None (null). */
    fun selectionAfterDelete(
        remaining: List<RigProfile>,
        deletedId: String,
        currentSelection: String?,
    ): String? =
        currentSelection?.takeIf { sel ->
            sel != deletedId && remaining.any { it.id == sel }
        }
}
