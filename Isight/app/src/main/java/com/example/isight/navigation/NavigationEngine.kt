package com.example.isight.navigation

import com.example.isight.mapping.SemanticMap
import com.example.isight.mapping.Vec3

enum class NavigationStatus { IDLE, NAVIGATING, ARRIVED, NO_PATH }

data class NavigationGuidance(
    val status: NavigationStatus,
    val targetRoomName: String?,
    val distanceMetersToNextWaypoint: Float?,
    val message: String
)

/**
 * Builds a coarse room-to-room topological graph from the semantic map
 * (room centers as nodes, each connected to its nearest neighbors) and
 * plans a path with [AStarPlanner].
 *
 * This is deliberately a WAYPOINT-LEVEL planner only. Moment-to-moment
 * obstacle avoidance around live people/furniture is [SafetyEngine]'s job,
 * driven by real-time YOLO+depth data — this graph has no idea what's
 * physically in front of the user right now, on purpose, matching this
 * project's architecture split between global path planning and reactive
 * safety.
 */
class NavigationEngine {

    private val graph = NavigationGraph()
    private var currentPath: List<NavNode> = emptyList()
    private var pathIndex = 0
    private var targetRoomName: String? = null

    private companion object {
        const val ARRIVAL_RADIUS_METERS = 0.6f
        const val NEAREST_ROOM_NEIGHBORS = 2
    }

    fun rebuildGraph(map: SemanticMap) {
        graph.clear()
        for (room in map.rooms.values) {
            graph.addNode(NavNode(room.id, room.center))
        }
        for (room in map.rooms.values) {
            map.rooms.values
                .filter { it.id != room.id }
                .sortedBy { it.center.distanceTo(room.center) }
                .take(NEAREST_ROOM_NEIGHBORS)
                .forEach { graph.addEdge(room.id, it.id) }
        }
    }

    fun startNavigationToRoom(map: SemanticMap, currentPosition: Vec3, roomName: String): NavigationGuidance {
        val targetRoom = map.findRoomByName(roomName)
            ?: return guidance(NavigationStatus.NO_PATH, roomName, "I don't know a room called $roomName yet.")

        val startNode = graph.nearestNode(currentPosition)
            ?: return guidance(NavigationStatus.NO_PATH, roomName, "I don't have a map to navigate with yet.")

        val path = AStarPlanner.findPath(graph, startNode.id, targetRoom.id)
            ?: return guidance(NavigationStatus.NO_PATH, roomName, "I can't find a path to the $roomName.")

        currentPath = path
        pathIndex = 0
        targetRoomName = roomName

        return guidance(NavigationStatus.NAVIGATING, roomName, "Heading to the $roomName.")
    }

    fun stop() {
        currentPath = emptyList()
        pathIndex = 0
        targetRoomName = null
    }

    val isNavigating: Boolean get() = targetRoomName != null

    /** Call periodically (e.g. once per second) with the user's current MAP-frame position. */
    fun update(currentPosition: Vec3): NavigationGuidance {
        val target = targetRoomName ?: return guidance(NavigationStatus.IDLE, null, "")

        if (currentPath.isEmpty() || pathIndex >= currentPath.size) {
            return guidance(NavigationStatus.ARRIVED, target, "$target reached.", 0f)
        }

        var waypoint = currentPath[pathIndex]
        var distance = currentPosition.distanceTo(waypoint.position)

        if (distance < ARRIVAL_RADIUS_METERS) {
            pathIndex++
            if (pathIndex >= currentPath.size) {
                stop()
                return guidance(NavigationStatus.ARRIVED, target, "$target reached.", 0f)
            }
            waypoint = currentPath[pathIndex]
            distance = currentPosition.distanceTo(waypoint.position)
        }

        return guidance(
            NavigationStatus.NAVIGATING,
            target,
            "%s ahead. Continue for %.1f meters.".format(target, distance),
            distance
        )
    }

    private fun guidance(
        status: NavigationStatus,
        target: String?,
        message: String,
        distance: Float? = null
    ) = NavigationGuidance(status, target, distance, message)
}
