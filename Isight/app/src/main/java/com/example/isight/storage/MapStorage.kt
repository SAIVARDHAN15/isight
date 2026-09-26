package com.example.isight.storage

import android.content.Context
import android.util.Log
import com.example.isight.mapping.Landmark
import com.example.isight.mapping.MapObject
import com.example.isight.mapping.Room
import com.example.isight.mapping.SemanticMap
import com.example.isight.mapping.Vec3
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "MapStorage"
private const val MAP_FILE_NAME = "isight_semantic_map.json"
private const val SCHEMA_VERSION = 1

/**
 * Persists a [SemanticMap] as JSON in app-private internal storage (no new
 * dependency needed — org.json ships with Android). This is what makes the
 * map survive an app restart; see RelocalizationManager for how a NEW
 * ARCore session finds its way back into this map's coordinate frame.
 */
object MapStorage {

    fun save(context: Context, map: SemanticMap) {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("mapId", map.mapId)
        root.put("createdAtEpochMillis", map.createdAtEpochMillis)
        root.put("rooms", map.rooms.values.map { roomToJson(it) }.toJsonArray())
        root.put("objects", map.objects.values.map { objectToJson(it) }.toJsonArray())
        root.put("landmarks", map.landmarks.values.map { landmarkToJson(it) }.toJsonArray())

        val file = File(context.filesDir, MAP_FILE_NAME)
        val tempFile = File(context.filesDir, "$MAP_FILE_NAME.tmp")
        tempFile.writeText(root.toString())
        // Write-to-temp-then-rename: a crash mid-write never corrupts the
        // last known-good map.
        if (!tempFile.renameTo(file)) {
            Log.e(TAG, "Failed to commit map file (rename failed)")
        }
    }

    fun load(context: Context): SemanticMap? {
        val file = File(context.filesDir, MAP_FILE_NAME)
        if (!file.exists()) return null

        return try {
            val root = JSONObject(file.readText())
            if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) {
                // Refuse to guess-migrate an unknown schema rather than risk
                // silently loading a map wrong.
                Log.w(TAG, "Unknown map schema version, refusing to load")
                return null
            }

            val map = SemanticMap(
                mapId = root.getString("mapId"),
                createdAtEpochMillis = root.getLong("createdAtEpochMillis")
            )

            root.getJSONArray("rooms").forEachObject { json ->
                val room = Room(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    center = jsonToVec(json.getJSONObject("center")),
                    referenceHeadingDegrees = json.optDouble("referenceHeadingDegrees", 0.0).toFloat()
                )
                room.objectIds.addAll(json.getJSONArray("objectIds").toStringList())
                room.landmarkIds.addAll(json.getJSONArray("landmarkIds").toStringList())
                map.addOrUpdateRoom(room)
            }

            root.getJSONArray("objects").forEachObject { json ->
                map.upsertObject(
                    MapObject(
                        id = json.getString("id"),
                        label = json.getString("label"),
                        position = jsonToVec(json.getJSONObject("position")),
                        confidence = json.getDouble("confidence").toFloat(),
                        roomId = json.optStringOrNull("roomId"),
                        lastSeenEpochMillis = json.getLong("lastSeenEpochMillis"),
                        isStatic = json.getBoolean("isStatic")
                    )
                )
            }

            root.getJSONArray("landmarks").forEachObject { json ->
                map.landmarks[json.getString("id")] = Landmark(
                    id = json.getString("id"),
                    label = json.getString("label"),
                    position = jsonToVec(json.getJSONObject("position"))
                )
            }

            map
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load map file, treating as absent", e)
            null
        }
    }

    fun deleteSavedMap(context: Context) {
        File(context.filesDir, MAP_FILE_NAME).delete()
    }

    private fun roomToJson(room: Room) = JSONObject().apply {
        put("id", room.id)
        put("name", room.name)
        put("center", vecToJson(room.center))
        put("referenceHeadingDegrees", room.referenceHeadingDegrees)
        put("objectIds", room.objectIds.toJsonArray())
        put("landmarkIds", room.landmarkIds.toJsonArray())
    }

    private fun objectToJson(obj: MapObject) = JSONObject().apply {
        put("id", obj.id)
        put("label", obj.label)
        put("position", vecToJson(obj.position))
        put("confidence", obj.confidence)
        put("roomId", obj.roomId)
        put("lastSeenEpochMillis", obj.lastSeenEpochMillis)
        put("isStatic", obj.isStatic)
    }

    private fun landmarkToJson(landmark: Landmark) = JSONObject().apply {
        put("id", landmark.id)
        put("label", landmark.label)
        put("position", vecToJson(landmark.position))
    }

    private fun vecToJson(vec: Vec3) = JSONObject().apply {
        put("x", vec.x)
        put("y", vec.y)
        put("z", vec.z)
    }

    private fun jsonToVec(json: JSONObject) = Vec3(
        json.getDouble("x").toFloat(),
        json.getDouble("y").toFloat(),
        json.getDouble("z").toFloat()
    )

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun List<JSONObject>.toJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { array.put(it) }
        return array
    }

    private fun List<String>.toJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { array.put(it) }
        return array
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }

    private inline fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
        for (i in 0 until length()) action(getJSONObject(i))
    }
}
