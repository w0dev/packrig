package net.ft8vc.app.settings

import net.ft8vc.rig.PttMethod
import net.ft8vc.rig.RigProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Codec for the RIG_PROFILES DataStore value: `{"v":1,"profiles":[...]}`.
 * Decode is fail-closed to an empty list — a corrupt value must never crash
 * settings; the operator just re-adds their rig. Field names are persisted;
 * never rename ("id","name","preset","protocol","baud","port","ptt").
 */
object RigProfileJson {

    private const val VERSION = 1

    fun encode(profiles: List<RigProfile>): String {
        val array = JSONArray()
        profiles.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("preset", p.presetId)
            p.catProtocolId?.let { o.put("protocol", it) }
            p.baud?.let { o.put("baud", it) }
            p.catPortIndex?.let { o.put("port", it) }
            p.pttMethod?.let { o.put("ptt", it.name) }
            array.put(o)
        }
        return JSONObject().put("v", VERSION).put("profiles", array).toString()
    }

    fun decode(raw: String?): List<RigProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            if (root.getInt("v") != VERSION) return emptyList()
            val array = root.getJSONArray("profiles")
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                RigProfile(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    presetId = o.getString("preset"),
                    catProtocolId = o.optStringOrNull("protocol"),
                    baud = if (o.has("baud")) o.getInt("baud") else null,
                    catPortIndex = if (o.has("port")) o.getInt("port") else null,
                    pttMethod = o.optStringOrNull("ptt")?.let { name ->
                        PttMethod.entries.firstOrNull { it.name == name }
                    },
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null
}
