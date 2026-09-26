package com.example.isight.mapping

import android.content.Context
import com.example.isight.storage.MapStorage
import java.util.UUID

/**
 * Owns the single in-memory [SemanticMap] for this app run: loads it from
 * disk on construction if one exists, otherwise starts a fresh empty map.
 *
 * NOTE: this constructor does synchronous file I/O (reading + JSON-parsing
 * the saved map) on whatever thread constructs it — currently the main
 * thread, in MainActivity#onCreate. Accepted as a deliberate simplification:
 * the map file is small (home/room-scale JSON), so this is realistically a
 * sub-millisecond-to-few-millisecond read, not worth an async-loading state
 * machine for a hackathon prototype. Revisit if map files grow large.
 */
class MapRepository(private val context: Context) {

    var currentMap: SemanticMap
        private set

    var loadedFromDisk: Boolean = false
        private set

    init {
        val loaded = MapStorage.load(context)
        if (loaded != null) {
            currentMap = loaded
            loadedFromDisk = true
        } else {
            currentMap = newEmptyMap()
            loadedFromDisk = false
        }
    }

    fun save() {
        MapStorage.save(context, currentMap)
    }

    fun startNewMap() {
        currentMap = newEmptyMap()
        loadedFromDisk = false
    }

    fun deleteSavedMap() {
        MapStorage.deleteSavedMap(context)
    }

    private fun newEmptyMap() = SemanticMap(
        mapId = UUID.randomUUID().toString(),
        createdAtEpochMillis = System.currentTimeMillis()
    )
}
