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
        val index = profiles.indexOfFirst { it.id == profile.id }
        return when {
            index >= 0 -> profiles.toMutableList().also { it[index] = profile }
            profiles.size >= MAX -> null
            else -> profiles + profile
        }
    }

    fun delete(profiles: List<RigProfile>, id: String): List<RigProfile> =
        profiles.filterNot { it.id == id }

    /** New selection after a delete: keep current if it survives, else first remaining. */
    fun selectionAfterDelete(
        remaining: List<RigProfile>,
        deletedId: String,
        currentSelection: String?,
    ): String? =
        if (currentSelection != null && currentSelection != deletedId &&
            remaining.any { it.id == currentSelection }
        ) {
            currentSelection
        } else {
            remaining.firstOrNull()?.id
        }
}
