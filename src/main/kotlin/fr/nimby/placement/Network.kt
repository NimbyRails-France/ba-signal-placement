package fr.nimby.placement

/** Sens géométrique local ; un raccord B-B inverse ce sens sur la voie suivante. */
enum class Direction(val sign: Int) { A_TO_B(1), B_TO_A(-1) }
enum class End { A, B }

/**
 * Connexion issue de la lecture SDK. Un lien natif nul doit devenir
 * Unknown, jamais EndOfTrack : les observations actuelles peuvent être incomplètes.
 */
sealed interface Connection {
    data object Unknown : Connection
    data object EndOfTrack : Connection
    data object Junction : Connection
    data class Join(val trackId: Long, val entry: End) : Connection
}

/**
 * Abscisses curvilignes en mètres depuis A, pas des fractions natives.
 * lengthM doit être la longueur réelle de la courbe, pas la corde A-B.
 * junctionsM contient TOUTES les aiguilles, y compris abordées en talon.
 * Une aiguille à une extrémité se représente également par Connection.Junction.
 */
data class Track(
    val id: Long,
    val lengthM: Double,
    val a: Connection = Connection.Unknown,
    val b: Connection = Connection.Unknown,
    val junctionsM: List<Double> = emptyList(),
) {
    fun connection(end: End): Connection = if (end == End.A) a else b
}

/** L'identité du modèle reste celle du signal source, sans interpréter son aspect. */
data class Signal(val id: Long, val trackId: Long, val offsetM: Double, val direction: Direction)

/**
 * Copie des observations nécessaires au calcul, préparée par NativeNetwork.
 * PlacementWorkflow contrôle de nouveau la session et recalcule depuis une
 * nouvelle capture avant de poser. Cette copie ne rend pas la lecture du jeu
 * atomique ; une heure de capture ne prouve pas une topologie inchangée.
 */
class NetworkSnapshot(
    val session: String,
    val revision: String,
    tracks: List<Track>,
    signals: List<Signal>,
) {
    internal val tracks = tracks.map { it.copy(junctionsM = it.junctionsM.toList()) }.associateBy { it.id }
    internal val signals = signals.toList()

    init {
        require(session.isNotBlank() && revision.isNotBlank())
        require(this.tracks.size == tracks.size && tracks.none { it.id == 0L }) { "Voies dupliquées ou sans identité" }
        for (track in tracks) {
            require(track.lengthM.isFinite() && track.lengthM > 0) { "Longueur curviligne indisponible" }
            require(track.junctionsM.all { it.isFinite() && it in 0.0..track.lengthM })
        }
        require(signals.map { it.id }.toSet().size == signals.size && signals.none { it.id == 0L })
        for (signal in signals) {
            val track = requireNotNull(this.tracks[signal.trackId]) { "Voie du signal absente" }
            require(signal.offsetM.isFinite() && signal.offsetM in 0.0..track.lengthM)
        }
    }
}
