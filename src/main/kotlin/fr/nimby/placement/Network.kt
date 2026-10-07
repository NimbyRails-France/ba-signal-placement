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

/** Owned, indexed observations. The planner requests only its bounded route;
 * adapters need not rebuild the unrelated parts of a large map. */
interface PlacementNetwork {
    val session: String
    val revision: String
    fun track(id: Long): Track?
    fun signal(id: Long): Signal?
    fun forEachSignalOnTrack(trackId: Long, action: (Signal) -> Unit)
}

/**
 * Copie validée des observations nécessaires au calcul, indépendante du SDK.
 * PlacementWorkflow contrôle de nouveau la session et recalcule depuis une
 * nouvelle capture avant de poser. Cette copie ne rend pas la lecture du jeu
 * atomique ; une heure de capture ne prouve pas une topologie inchangée.
 */
class NetworkSnapshot(
    override val session: String,
    override val revision: String,
    tracks: List<Track>,
    signals: List<Signal>,
) : PlacementNetwork {
    private val tracks = LinkedHashMap<Long, Track>(tracks.size)
    private val signalsById = HashMap<Long, Signal>(signals.size)
    // Most tracks carry zero or one signal: retain the value directly and only
    // allocate a bucket for tracks that actually need several entries.
    private val signalsByTrack = HashMap<Long, Any>(minOf(signals.size, tracks.size))

    override fun track(id: Long): Track? = tracks[id]
    override fun signal(id: Long): Signal? = signalsById[id]
    override fun forEachSignalOnTrack(trackId: Long, action: (Signal) -> Unit) {
        when (val entry = signalsByTrack[trackId]) {
            null -> Unit
            is Signal -> action(entry)
            else -> {
                @Suppress("UNCHECKED_CAST")
                for (signal in entry as List<Signal>) action(signal)
            }
        }
    }

    init {
        require(session.isNotBlank() && revision.isNotBlank())
        for (track in tracks) {
            require(track.id != 0L && track.id !in this.tracks) { "Voies dupliquées ou sans identité" }
            require(track.lengthM.isFinite() && track.lengthM > 0) { "Longueur curviligne indisponible" }
            require(track.junctionsM.all { it.isFinite() && it in 0.0..track.lengthM })
            this.tracks[track.id] = track.copy(junctionsM = track.junctionsM.sorted())
        }
        for (signal in signals) {
            require(signal.id != 0L && signalsById.put(signal.id, signal) == null)
            val track = requireNotNull(this.tracks[signal.trackId]) { "Voie du signal absente" }
            require(signal.offsetM.isFinite() && signal.offsetM in 0.0..track.lengthM)
            when (val existing = signalsByTrack[signal.trackId]) {
                null -> signalsByTrack[signal.trackId] = signal
                is Signal -> signalsByTrack[signal.trackId] = mutableListOf(existing, signal)
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    (existing as MutableList<Signal>).add(signal)
                }
            }
        }
    }
}
