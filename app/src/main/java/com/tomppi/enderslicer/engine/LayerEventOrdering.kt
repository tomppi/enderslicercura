package com.tomppi.enderslicer.engine

/** Stable layer/source ordering that never uses lexical event IDs as chronology. */
internal object LayerEventOrdering {
    fun normalize(events: List<LayerEvent>): List<LayerEvent> {
        val seenIds = hashSetOf<String>()
        return events.withIndex()
            .filter { seenIds.add(it.value.id) }
            .sortedWith(
                compareBy<IndexedValue<LayerEvent>>(
                    { it.value.layerNumber },
                    { it.value.source.ordinal },
                    { it.index },
                ),
            )
            .map(IndexedValue<LayerEvent>::value)
    }
}
