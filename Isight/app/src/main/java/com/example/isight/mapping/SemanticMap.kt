package com.example.isight.mapping

/** A position in the PERSISTENT map's own coordinate frame — NOT raw ARCore world coordinates; see RelocalizationManager. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    fun distanceTo(other: Vec3): Float {
        val dx = x - other.x
        val dy = y - other.y
        val dz = z - other.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }
}

data class MapObject(
    val id: String,
    val label: String,
    val position: Vec3,
    val confidence: Float,
    val roomId: String?,
    val lastSeenEpochMillis: Long,
    val isStatic: Boolean
)

/**
 * A stable visual/spatial reference point used for relocalization. See
 * RelocalizationManager for exactly how (and how approximately) these are
 * used — this project does not implement true visual feature matching.
 */
data class Landmark(
    val id: String,
    val label: String,
    val position: Vec3
)

data class Room(
    val id: String,
    var name: String,
    var center: Vec3 = Vec3(0f, 0f, 0f),
    /** Session heading (degrees) recorded at the moment this room was first named — see RelocalizationManager. */
    var referenceHeadingDegrees: Float = 0f,
    val objectIds: MutableList<String> = mutableListOf(),
    val landmarkIds: MutableList<String> = mutableListOf()
)

/**
 * A persistent, semantic representation of one indoor space: rooms,
 * objects, and landmarks, all in the MAP's own coordinate frame (see
 * [Vec3]). Deliberately does not store raw ARCore poses — those are
 * session-relative and meaningless across app restarts.
 */
class SemanticMap(
    val mapId: String,
    val createdAtEpochMillis: Long
) {
    val rooms = mutableMapOf<String, Room>()
    val objects = mutableMapOf<String, MapObject>()
    val landmarks = mutableMapOf<String, Landmark>()

    fun findRoomByName(name: String): Room? =
        rooms.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun findObjectsByLabel(label: String): List<MapObject> =
        objects.values.filter { it.label.equals(label, ignoreCase = true) }

    fun addOrUpdateRoom(room: Room) {
        rooms[room.id] = room
    }

    fun upsertObject(mapObject: MapObject) {
        objects[mapObject.id] = mapObject
        val roomId = mapObject.roomId ?: return
        rooms[roomId]?.let { room ->
            if (mapObject.id !in room.objectIds) room.objectIds.add(mapObject.id)
        }
    }

    fun addLandmark(landmark: Landmark, roomId: String? = null) {
        landmarks[landmark.id] = landmark
        roomId?.let { id ->
            rooms[id]?.let { room ->
                if (landmark.id !in room.landmarkIds) room.landmarkIds.add(landmark.id)
            }
        }
    }
}
