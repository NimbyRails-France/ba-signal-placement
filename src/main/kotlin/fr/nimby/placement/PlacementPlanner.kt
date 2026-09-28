package fr.nimby.placement

import kotlin.math.abs

data class PlacementRequest(
    val sourceSignal: Long,
    val spacingM: Double,
    val clearanceM: Double = 1.0,
    val maxCandidates: Int = 1000,
    val maxTracks: Int = 4096,
) {
    init {
        require(spacingM.isFinite() && spacingM > 0)
        require(clearanceM.isFinite() && clearanceM >= 0 && clearanceM < spacingM / 2)
        require(maxCandidates in 1..100_000 && maxTracks in 1..100_000)
    }
}

enum class StopReason { JUNCTION, END_OF_TRACK, UNKNOWN_CONNECTION, INCONSISTENT_CONNECTION, CYCLE, TRACK_LIMIT, CANDIDATE_LIMIT }
data class Candidate(val trackId: Long, val offsetM: Double, val direction: Direction, val distanceFromSourceM: Double)
data class SkippedCandidate(val candidate: Candidate, val existingSignalIds: List<Long>)

/** Une prévisualisation seulement : cette valeur ne donne aucune autorisation d'écrire dans le jeu. */
data class PlacementPreview(
    val session: String,
    val revision: String,
    val sourceSignal: Long,
    val placements: List<Candidate>,
    val skipped: List<SkippedCandidate>,
    val stop: StopReason,
    val coveredDistanceM: Double,
)

/**
 * Calcule des multiples de l'espacement depuis le signal d'origine.
 * Ne choisit jamais une branche. S'arrête à toute aiguille, dans les deux sens.
 * Les courbes sont mesurées par leurs abscisses curvilignes fournies en entrée.
 * Aucun accès natif, aucune écriture, aucun type de signal imposé.
 */
object PlacementPlanner {
    private data class Section(val track: Track, val from: Double, val to: Double, val startM: Double, val direction: Direction) {
        val lengthM get() = abs(to - from)
        val endM get() = startM + lengthM
        fun contains(offset: Double) = offset in minOf(from, to)..maxOf(from, to)
    }

    fun preview(network: NetworkSnapshot, request: PlacementRequest): PlacementPreview {
        val source = requireNotNull(network.signals.find { it.id == request.sourceSignal }) { "Signal source absent" }
        val sections = mutableListOf<Section>()
        val visited = mutableSetOf<Long>()
        var track = network.tracks.getValue(source.trackId)
        var from = source.offsetM
        var direction = source.direction
        var covered = 0.0
        // One extra spacing bounds traversal even when every candidate is occupied.
        val budgetM = request.spacingM * (request.maxCandidates.toDouble() + 1)
        require(budgetM.isFinite()) { "Distance demandée trop grande" }
        val stop: StopReason
        while (true) {
            if (track.id in visited) { stop = StopReason.CYCLE; break }
            if (visited.size == request.maxTracks) { stop = StopReason.TRACK_LIMIT; break }
            visited.add(track.id)
            val junction = track.junctionsM.filter { (it - from) * direction.sign >= 0 }
                .minByOrNull { abs(it - from) }
            val exit = if (direction == Direction.A_TO_B) End.B else End.A
            val end = junction ?: if (exit == End.B) track.lengthM else 0.0
            val length = abs(end - from)
            if (length > budgetM - covered) {
                sections += Section(track, from, from + direction.sign * (budgetM - covered), covered, direction)
                covered = budgetM
                stop = StopReason.CANDIDATE_LIMIT
                break
            }
            sections += Section(track, from, end, covered, direction)
            covered += length
            if (junction != null) { stop = StopReason.JUNCTION; break }
            when (val connection = track.connection(exit)) {
                Connection.Junction -> { stop = StopReason.JUNCTION; break }
                Connection.EndOfTrack -> { stop = StopReason.END_OF_TRACK; break }
                Connection.Unknown -> { stop = StopReason.UNKNOWN_CONNECTION; break }
                is Connection.Join -> {
                    val next = network.tracks[connection.trackId]
                    if (next == null) { stop = StopReason.UNKNOWN_CONNECTION; break }
                    // A reciprocal endpoint avoids following a one-sided or stale link.
                    // Junction classification takes precedence over reciprocal validation.
                    if (next.connection(connection.entry) == Connection.Junction) { stop = StopReason.JUNCTION; break }
                    if (next.connection(connection.entry) != Connection.Join(track.id, exit)) {
                        stop = StopReason.INCONSISTENT_CONNECTION
                        break
                    }
                    track = next
                    direction = if (connection.entry == End.A) Direction.A_TO_B else Direction.B_TO_A
                    from = if (connection.entry == End.A) 0.0 else next.lengthM
                }
            }
        }

        // Project all observed signals, of either direction, onto the traced route.
        // This also detects collisions across a seam between two track records.
        val signalsByTrack = network.signals.groupBy { it.trackId }
        val occupied = sections.flatMap { section ->
            signalsByTrack[section.track.id].orEmpty().filter { section.contains(it.offsetM) }.map {
                it.id to section.startM + abs(it.offsetM - section.from)
            }
        }.sortedBy { it.second }
        val placements = mutableListOf<Candidate>()
        val skipped = mutableListOf<SkippedCandidate>()
        var sectionIndex = 0
        var occupiedIndex = 0
        for (index in 1..request.maxCandidates) {
            val distance = index * request.spacingM
            // Never place on the terminal boundary (switch, unknown route, or end).
            if (distance >= covered || covered - distance <= request.clearanceM) break
            // Exact internal seams belong to the next section, ensuring its direction
            // is used after A-A/B-B connections. They are not terminal boundaries.
            while (sectionIndex < sections.lastIndex && distance >= sections[sectionIndex].endM) sectionIndex++
            val section = sections[sectionIndex]
            val offset = (section.from + section.direction.sign * (distance - section.startM)).coerceIn(0.0, section.track.lengthM)
            val candidate = Candidate(section.track.id, offset, section.direction, distance)
            while (occupiedIndex < occupied.size && occupied[occupiedIndex].second < distance - request.clearanceM) occupiedIndex++
            val nearby = mutableListOf<Long>()
            var cursor = occupiedIndex
            while (cursor < occupied.size && occupied[cursor].second <= distance + request.clearanceM) nearby += occupied[cursor++].first
            if (nearby.isEmpty()) placements += candidate else skipped += SkippedCandidate(candidate, nearby)
        }
        return PlacementPreview(network.session, network.revision, source.id, placements.toList(), skipped.toList(),
            stop, covered)
    }
}
