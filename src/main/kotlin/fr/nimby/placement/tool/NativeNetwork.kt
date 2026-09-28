package fr.nimby.placement.tool

import fr.nimby.placement.*
import fr.nimby.placement.Signal
import nimby.*

/** Copied observation -> pure planner. Missing links are unknown, never a
 * claim that the track ends. Both ends of every observed switch stop traversal. */
object NativeNetwork {
    data class Preview(val plan: PlacementPreview, val positions: List<SignalPosition>)

    fun preview(value: ToolNetwork, sourceId: Long, spacingM: Int): Preview {
        val nodes = value.tracks.associateBy { it.id }
        require(nodes.size == value.tracks.size) { "Voies dupliquées" }
        require(value.signals.map { it.id }.distinct().size == value.signals.size) { "Signaux dupliqués" }
        val source = requireNotNull(value.signals.find { it.id == sourceId }) { "Signal source absent" }
        require(nodes[source.track]?.lengthM != null) { "Longueur du signal source indisponible" }
        val junctions = mutableMapOf<Long, MutableList<Double>>()
        val branchEnds = mutableSetOf<Pair<Long, End>>()
        for (j in value.junctions) {
            require(j.mainTrack != j.branchTrack && j.fraction.isFinite() && j.fraction in 0.0..1.0 &&
                j.mainDirection in listOf(-1, 1) && j.branchDirection in listOf(-1, 1)) { "Raccordement invalide" }
            nodes[j.mainTrack]?.lengthM?.let { junctions.getOrPut(j.mainTrack) { mutableListOf() } += it * j.fraction }
            branchEnds += j.branchTrack to if(j.branchDirection == 1) End.A else End.B
        }
        fun link(id: Long, end: End): Connection {
            if(id to end in branchEnds) return Connection.Junction
            val node = nodes.getValue(id)
            val other = nodes[if(end == End.A) node.linkA else node.linkB] ?: return Connection.Unknown
            if(other.lengthM == null || (other.linkA == id) == (other.linkB == id)) return Connection.Unknown
            return Connection.Join(other.id, if(other.linkA == id) End.A else End.B)
        }
        val tracks = nodes.values.mapNotNull { n -> n.lengthM?.let { Track(n.id, it, link(n.id, End.A), link(n.id, End.B), junctions[n.id].orEmpty()) } }
        val signals = value.signals.filter { nodes[it.track]?.lengthM != null }.map { s ->
            require(s.direction in listOf(-1,1) && s.fraction.isFinite() && s.fraction in 0.0..1.0) { "Position de signal invalide" }
            val forward = if(s.kind == 4) -s.direction else s.direction
            Signal(s.id,s.track,nodes.getValue(s.track).lengthM!! * s.fraction,if(forward == 1) Direction.A_TO_B else Direction.B_TO_A)
        }
        val network = NetworkSnapshot(value.worldId,value.generation.toString(),tracks,signals)
        val plan = PlacementPlanner.preview(network,PlacementRequest(sourceId,spacingM.toDouble(),maxCandidates=64))
        val positions = plan.placements.map {
            val fraction = it.offsetM / nodes.getValue(it.trackId).lengthM!!
            require(fraction > 0 && fraction < 1) { "Un emplacement tombe sur une extrémité : modifier l'espacement" }
            SignalPosition(it.trackId,fraction,if(source.kind == 4) -it.direction.sign else it.direction.sign)
        }
        return Preview(plan,positions)
    }
}
