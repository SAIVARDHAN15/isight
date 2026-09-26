package com.example.isight.navigation

import com.example.isight.mapping.Vec3

data class NavNode(val id: String, val position: Vec3)
data class NavEdge(val fromId: String, val toId: String, val cost: Float)

/** Simple weighted graph: nodes = walkable positions, edges = traversable connections. */
class NavigationGraph {

    private val nodes = mutableMapOf<String, NavNode>()
    private val adjacency = mutableMapOf<String, MutableList<NavEdge>>()

    fun addNode(node: NavNode) {
        nodes[node.id] = node
        adjacency.getOrPut(node.id) { mutableListOf() }
    }

    fun addEdge(fromId: String, toId: String) {
        val from = nodes[fromId] ?: return
        val to = nodes[toId] ?: return
        val cost = from.position.distanceTo(to.position)
        adjacency.getOrPut(fromId) { mutableListOf() }.add(NavEdge(fromId, toId, cost))
        adjacency.getOrPut(toId) { mutableListOf() }.add(NavEdge(toId, fromId, cost))
    }

    fun node(id: String): NavNode? = nodes[id]
    fun neighbors(id: String): List<NavEdge> = adjacency[id] ?: emptyList()
    fun nearestNode(position: Vec3): NavNode? = nodes.values.minByOrNull { it.position.distanceTo(position) }

    fun clear() {
        nodes.clear()
        adjacency.clear()
    }
}
