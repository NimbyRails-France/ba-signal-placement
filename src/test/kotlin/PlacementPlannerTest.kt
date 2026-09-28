package fr.nimby.placement

import kotlin.test.*

class PlacementPlannerTest {
    private fun snapshot(tracks: List<Track>, source: Signal = Signal(10, 1, 0.0, Direction.A_TO_B), others: List<Signal> = emptyList()) =
        NetworkSnapshot("save-session", "topology-1", tracks, listOf(source) + others)
    private fun plan(network: NetworkSnapshot, spacing: Double = 100.0, clearance: Double = 0.0, candidates: Int = 1000, tracks: Int = 4096) =
        PlacementPlanner.preview(network, PlacementRequest(10, spacing, clearance, candidates, tracks))

    @Test fun followsCurvilinearDistanceAndStopsBeforeSwitch() {
        // The curved rail has a measured length of 900 m. No chord is supplied.
        val result = plan(snapshot(listOf(Track(1, 900.0, junctionsM = listOf(350.0, 800.0)))))
        assertEquals(listOf(100.0, 200.0, 300.0), result.placements.map { it.offsetM })
        assertEquals(StopReason.JUNCTION, result.stop)
        assertEquals(350.0, result.coveredDistanceM)
    }

    @Test fun reverseDirectionStopsAtFirstEncounteredSwitch() {
        val result = plan(snapshot(listOf(Track(1, 900.0, junctionsM = listOf(350.0, 800.0))), Signal(10, 1, 750.0, Direction.B_TO_A)))
        assertEquals(listOf(650.0, 550.0, 450.0), result.placements.map { it.offsetM })
        assertTrue(result.placements.all { it.direction == Direction.B_TO_A })
        assertEquals(StopReason.JUNCTION, result.stop)
    }

    @Test fun placementExactlyAtSwitchIsExcluded() {
        val result = plan(snapshot(listOf(Track(1, 900.0, junctionsM = listOf(300.0)))))
        assertEquals(listOf(100.0, 200.0), result.placements.map { it.offsetM })
    }

    @Test fun switchAtSourceDoesNotChooseBranch() {
        val result = plan(snapshot(listOf(Track(1, 900.0, junctionsM = listOf(0.0)))))
        assertTrue(result.placements.isEmpty())
        assertEquals(StopReason.JUNCTION, result.stop)
    }

    @Test fun originDoesNotHaveToBeTrackStart() {
        val result = plan(snapshot(listOf(Track(1, 500.0, b = Connection.EndOfTrack)), Signal(10, 1, 125.0, Direction.A_TO_B)))
        assertEquals(listOf(225.0, 325.0, 425.0), result.placements.map { it.offsetM })
    }

    @Test fun carriesRemainderAcrossTracks() {
        val result = plan(snapshot(listOf(
            Track(1, 150.0, b = Connection.Join(2, End.A)),
            Track(2, 400.0, a = Connection.Join(1, End.B), b = Connection.EndOfTrack),
        )))
        assertEquals(listOf(1L, 2L, 2L, 2L, 2L), result.placements.map { it.trackId })
        assertEquals(listOf(100.0, 50.0, 150.0, 250.0, 350.0), result.placements.map { it.offsetM })
        assertEquals(StopReason.END_OF_TRACK, result.stop)
    }

    @Test fun bToBJoinChangesLocalDirectionAtExactSeam() {
        val result = plan(snapshot(listOf(
            Track(1, 100.0, b = Connection.Join(2, End.B)),
            Track(2, 250.0, a = Connection.EndOfTrack, b = Connection.Join(1, End.B)),
        )))
        assertEquals(listOf(250.0, 150.0, 50.0), result.placements.map { it.offsetM })
        assertTrue(result.placements.all { it.trackId == 2L && it.direction == Direction.B_TO_A })
    }

    @Test fun aToAJoinChangesLocalDirection() {
        val result = plan(snapshot(listOf(
            Track(1, 150.0, a = Connection.Join(2, End.A)),
            Track(2, 250.0, a = Connection.Join(1, End.A), b = Connection.EndOfTrack),
        ), Signal(10, 1, 150.0, Direction.B_TO_A)))
        assertEquals(listOf(50.0, 50.0, 150.0), result.placements.map { it.offsetM })
        assertEquals(listOf(Direction.B_TO_A, Direction.A_TO_B, Direction.A_TO_B), result.placements.map { it.direction })
    }

    @Test fun unknownIsNotReportedAsBufferStop() {
        val result = plan(snapshot(listOf(Track(1, 300.0))))
        assertEquals(StopReason.UNKNOWN_CONNECTION, result.stop)
        assertEquals(2, result.placements.size)
    }

    @Test fun missingTrackAndNonreciprocalLinksStopTraversal() {
        assertEquals(StopReason.UNKNOWN_CONNECTION, plan(snapshot(listOf(Track(1, 300.0, b = Connection.Join(2, End.A))))).stop)
        val result = plan(snapshot(listOf(Track(1, 300.0, b = Connection.Join(2, End.A)), Track(2, 300.0))))
        assertEquals(StopReason.INCONSISTENT_CONNECTION, result.stop)
        assertTrue(result.placements.all { it.trackId == 1L })
    }

    @Test fun trailingSwitchAtEntryAlsoStopsTraversal() {
        val result = plan(snapshot(listOf(
            Track(1, 300.0, b = Connection.Join(2, End.A)), Track(2, 300.0, a = Connection.Junction),
        )))
        assertEquals(StopReason.JUNCTION, result.stop)
        assertTrue(result.placements.all { it.trackId == 1L })
    }

    @Test fun existingSignalsInEitherDirectionSkipSlotWithoutResettingSpacing() {
        val result = plan(snapshot(listOf(Track(1, 450.0)), others = listOf(
            Signal(11, 1, 101.0, Direction.A_TO_B), Signal(12, 1, 300.0, Direction.B_TO_A),
        )), clearance = 2.0)
        assertEquals(listOf(200.0, 400.0), result.placements.map { it.offsetM })
        assertEquals(listOf(listOf(11L), listOf(12L)), result.skipped.map { it.existingSignalIds })
    }

    @Test fun collisionDetectionCrossesTrackSeam() {
        val result = plan(snapshot(listOf(
            Track(1, 101.0, b = Connection.Join(2, End.A)),
            Track(2, 300.0, a = Connection.Join(1, End.B)),
        ), others = listOf(Signal(11, 2, 0.5, Direction.B_TO_A))), clearance = 2.0)
        assertEquals(100.0, result.skipped.single().candidate.distanceFromSourceM)
        assertEquals(listOf(11L), result.skipped.single().existingSignalIds)
    }

    @Test fun clearanceKeepsPlacementsAwayFromTerminalBoundary() {
        val result = plan(snapshot(listOf(Track(1, 900.0, junctionsM = listOf(301.0)))), clearance = 2.0)
        assertEquals(listOf(100.0, 200.0), result.placements.map { it.offsetM })
    }

    @Test fun shortTrackAndCycleTerminate() {
        assertTrue(plan(snapshot(listOf(Track(1, 50.0)))).placements.isEmpty())
        val result = plan(snapshot(listOf(
            Track(1, 150.0, a = Connection.Join(2, End.B), b = Connection.Join(2, End.A)),
            Track(2, 150.0, a = Connection.Join(1, End.B), b = Connection.Join(1, End.A)),
        )))
        assertEquals(StopReason.CYCLE, result.stop)
        assertEquals(listOf(100.0, 200.0), result.placements.map { it.distanceFromSourceM })
    }

    @Test fun budgetsBoundWorkEvenIfEverySlotIsAlreadyOccupied() {
        val result = plan(snapshot(listOf(Track(1, 10000.0)), others = listOf(
            Signal(11, 1, 100.0, Direction.A_TO_B), Signal(12, 1, 200.0, Direction.A_TO_B),
        )), candidates = 2)
        assertEquals(StopReason.CANDIDATE_LIMIT, result.stop)
        assertEquals(2, result.skipped.size)
        assertTrue(result.placements.isEmpty())
        val trackLimited = plan(snapshot(listOf(
            Track(1, 150.0, b = Connection.Join(2, End.A)), Track(2, 150.0, a = Connection.Join(1, End.B)),
        )), tracks = 1)
        assertEquals(StopReason.TRACK_LIMIT, trackLimited.stop)
    }

    @Test fun invalidInputsAreRejectedRatherThanInvented() {
        for (spacing in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { PlacementRequest(10, spacing) }
        }
        assertFailsWith<IllegalArgumentException> { PlacementRequest(10, 100.0, clearanceM = 50.0) }
        assertFailsWith<IllegalArgumentException> { snapshot(listOf(Track(1, Double.NaN))) }
        assertFailsWith<IllegalArgumentException> { snapshot(listOf(Track(1, 100.0), Track(1, 100.0))) }
        assertFailsWith<IllegalArgumentException> { snapshot(listOf(Track(1, 100.0)), Signal(10, 1, 101.0, Direction.A_TO_B)) }
        assertFailsWith<IllegalArgumentException> { PlacementPlanner.preview(snapshot(listOf(Track(1, 100.0))), PlacementRequest(999, 100.0)) }
    }

    @Test fun snapshotDefensivelyCopiesJunctionsAndRetainsProvenance() {
        val junctions = mutableListOf(250.0)
        val network = snapshot(listOf(Track(1, 900.0, junctionsM = junctions)))
        junctions.clear()
        val result = plan(network)
        assertEquals(250.0, result.coveredDistanceM)
        assertEquals("save-session", result.session)
        assertEquals("topology-1", result.revision)
        assertEquals(10L, result.sourceSignal)
    }
}
