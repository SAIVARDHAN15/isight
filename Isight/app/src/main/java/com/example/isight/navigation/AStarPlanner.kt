package com.example.isight.navigation

import java.util.PriorityQueue

/** Standard A* over a [NavigationGraph], using Euclidean distance as both edge cost and heuristic. */
object AStarPlanner {

    private data class ScoredNode(val id: String, val fScore: Float)

    fun findPath(graph: NavigationGraph, startId: String, goalId: String): List<NavNode>? {
        val goal = graph.node(goalId) ?: return null
        val start = graph.node(startId) ?: return null
        if (startId == goalId) return listOf(start)

        val openSet = PriorityQueue<ScoredNode>(compareBy { it.fScore })
        val gScore = mutableMapOf(startId to 0f)
        val cameFrom = mutableMapOf<String, String>()
        val visited = mutableSetOf<String>()

        openSet.add(ScoredNode(startId, start.position.distanceTo(goal.position)))

        while (openSet.isNotEmpty()) {
            val current = openSet.poll()
            if (current.id == goalId) {
                return reconstructPath(graph, cameFrom, current.id)
            }
            if (!visited.add(current.id)) continue

            for (edge in graph.neighbors(current.id)) {
                val tentativeG = (gScore[current.id] ?: Float.MAX_VALUE) + edge.cost
                if (tentativeG < (gScore[edge.toId] ?: Float.MAX_VALUE)) {
                    cameFrom[edge.toId] = current.id
                    gScore[edge.toId] = tentativeG
                    val neighborNode = graph.node(edge.toId)
                    val heuristic = neighborNode?.position?.distanceTo(goal.position) ?: 0f
                    openSet.add(ScoredNode(edge.toId, tentativeG + heuristic))
                }
            }
        }
        return null
    }

    private fun reconstructPath(graph: NavigationGraph, cameFrom: Map<String, String>, goalId: String): List<NavNode> {
        val ids = mutableListOf(goalId)
        var current = goalId
        while (cameFrom.containsKey(current)) {
            current = cameFrom.getValue(current)
            ids.add(current)
        }
        return ids.reversed().mapNotNull { graph.node(it) }
    }
}
