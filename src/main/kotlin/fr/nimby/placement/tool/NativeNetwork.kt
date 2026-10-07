package fr.nimby.placement.tool

import fr.nimby.placement.*
import fr.nimby.placement.Signal
import nimby.*

/** Copied observation -> pure planner. Missing links are unknown, never a
 * claim that the track ends. Both ends of every observed switch stop traversal. */
object NativeNetwork {
    data class Preview(val plan: PlacementPreview, val positions: List<SignalPosition>)

    fun preview(value: ToolNetwork, sourceId: Long, spacingM: Int): Preview {
        val topology = value.topology()
        val source = requireNotNull(topology.signal(sourceId)) { "Signal source ou longueur de sa voie indisponible" }
        val plan = PlacementPlanner.preview(PlannerNetwork(topology),PlacementRequest(sourceId,spacingM.toDouble(),maxCandidates=64))
        val positions = plan.placements.map {
            val fraction = it.offsetM / requireNotNull(topology.track(it.trackId)).lengthM
            require(fraction > 0 && fraction < 1) { "Un emplacement tombe sur une extrémité : modifier l'espacement" }
            source.placementAt(it.trackId, fraction, it.direction.sign)
        }
        return Preview(plan,positions)
    }

    /** The SDK already owns and validates this topology. Convert only route
     * records requested by the planner; retain no observations across captures. */
    private class PlannerNetwork(private val topology: ToolTopology) : PlacementNetwork {
        override val session = topology.worldId
        override val revision = topology.generation.toString()
        private val tracks = HashMap<Long, Track>()
        private var signalsByTrack: Map<Long, Any>? = null

        override fun track(id: Long): Track? {
            tracks[id]?.let { return it }
            val observed = topology.track(id) ?: return null
            return Track(observed.id, observed.lengthM,
                observed.connection(ToolTrackEnd.A).forPlanner(),
                observed.connection(ToolTrackEnd.B).forPlanner(), observed.junctionOffsetsM)
                .also { tracks[id] = it }
        }

        override fun signal(id: Long): Signal? = topology.signal(id)?.forPlanner()

        override fun forEachSignalOnTrack(trackId: Long, action: (Signal) -> Unit) {
            // One index pass per capture, not a full signal scan per route track.
            // A track with one signal needs no bucket allocation.
            var index = signalsByTrack
            if (index == null) {
                val prepared = HashMap<Long, Any>(minOf(topology.signals.size, topology.tracks.size))
                for (signal in topology.signals) when (val existing = prepared[signal.track]) {
                    null -> prepared[signal.track] = signal
                    is ToolSignal -> prepared[signal.track] = mutableListOf(existing, signal)
                    else -> {
                        @Suppress("UNCHECKED_CAST")
                        (existing as MutableList<ToolSignal>).add(signal)
                    }
                }
                signalsByTrack = prepared
                index = prepared
            }
            when (val entry = index[trackId]) {
                null -> Unit
                is ToolSignal -> action(entry.forPlanner())
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    for (signal in entry as List<ToolSignal>) action(signal.forPlanner())
                }
            }
        }

        private fun ToolSignal.forPlanner(): Signal = Signal(id, track,
            requireNotNull(topology.track(track)).lengthM * fraction,
            if (travelDirection == 1) Direction.A_TO_B else Direction.B_TO_A)

        private fun ToolTrackConnection.forPlanner(): Connection = when (this) {
            ToolTrackConnection.Unknown -> Connection.Unknown
            ToolTrackConnection.Junction -> Connection.Junction
            is ToolTrackConnection.Join -> Connection.Join(trackId, if (entry == ToolTrackEnd.A) End.A else End.B)
        }
    }
}
