package fr.nimby.placement.tool

import kotlin.test.*
import kotlin.time.measureTime
import nimby.*

/** Exercise the actual workflow on a quiet panel, including lease renewals.
 * Durations are diagnostic; deterministic work counts are the regression gate. */
class PlacementPerformanceTest {
    private class Port : PlacementPort {
        override val worldId = "world"
        override val generation = 1L
        var captures = 0
        var drawings = 0
        var panels = 0
        var modelChanges = 0
        var buttons: List<ToolButton>? = null
        var inputs: List<ToolNumberInput>? = null
        override fun network(): ToolNetwork {
            captures++
            return ToolNetwork(worldId, generation,
                listOf(ToolTrack(TRACK, null, null, 3_100.0)), emptyList(),
                listOf(ToolSignal(SOURCE, TRACK, 100.0 / 3_100, 1, 1)))
        }
        override fun showPreview(request: SignalActionRequest, positions: List<SignalPosition>) { drawings++ }
        override fun clearPreview() = Unit
        override fun panel(request: SignalActionRequest, message: String, buttons: List<ToolButton>, inputs: List<ToolNumberInput>) {
            panels++
            if (this.buttons !== buttons || this.inputs !== inputs) modelChanges++
            this.buttons = buttons
            this.inputs = inputs
        }
        override fun prepare(source: Long): ConstructionResult = error("Unexpected preparation")
        override fun create(ticket: Long, source: Long, positions: List<SignalPosition>): ConstructionResult = error("Unexpected creation")
        override fun undo(ticket: Long): ConstructionResult = error("Unexpected undo")
        override fun poll(ticket: Long): ConstructionResult = error("Unexpected polling")
    }

    @Test fun quietPreviewRenewsWithoutCapturingNetwork() {
        val workflow = PlacementWorkflow()
        val port = Port()
        val request = SignalActionRequest(1, SOURCE, "preview", "repeat.v1", "world", 1, 10, "repeat")
        workflow.click(request, port)
        repeat(500) { workflow.tick(port) }
        val elapsed = measureTime { repeat(20_000) { workflow.tick(port) } }
        assertEquals(1, port.captures)
        assertEquals(20_501, port.drawings)
        assertEquals(20_501, port.panels)
        assertEquals(1, port.modelChanges, "Quiet ticks must reuse the acknowledged controls")
        println("Placement quiet preview: 20000 ticks in $elapsed; ${port.modelChanges} panel models, ${port.captures} network capture")
    }

    @Test fun repeatedSpacingKeepsApprovalAndChangedSpacingCapturesImmediately() {
        val workflow = PlacementWorkflow()
        val port = Port()
        val request = SignalActionRequest(1, SOURCE, "preview", "repeat.v1", "world", 1, 10, "repeat")
        workflow.click(request, port)
        val buttons = port.buttons
        val inputs = port.inputs
        repeat(20) { workflow.click(request.copy(action="spacing", value=1000), port) }
        assertEquals(1, port.captures)
        assertSame(buttons, port.buttons)
        assertSame(inputs, port.inputs)
        assertTrue(port.buttons!!.single { it.id == "apply" }.enabled)
        workflow.click(request.copy(action="spacing", value=750), port)
        assertEquals(2, port.captures)
        assertEquals(750, port.inputs!!.single().value)
        assertTrue(port.buttons!!.single { it.id == "apply" }.enabled)
        workflow.tick(port)
        assertEquals(2, port.captures)
        assertEquals(2, port.modelChanges)
    }

    @Test fun largeUnrelatedNetworkKeepsTheSamePreview() {
        val tracks = List(20_000) { index -> ToolTrack(TRACK + index * 0x10000L, null, null, 3_100.0) }
        val signals = tracks.mapIndexed { index, track -> ToolSignal(SOURCE + index * 0x10000L, track.id, 100.0 / 3_100, 1, 1) }
        val network = ToolNetwork("world", 1, tracks, emptyList(), signals)
        val expected = NativeNetwork.preview(ToolNetwork("world", 1, tracks.take(1), emptyList(), signals.take(1)), SOURCE, 1000)
        repeat(3) { assertEquals(expected, NativeNetwork.preview(network, SOURCE, 1000)) }
        val elapsed = measureTime { repeat(10) { assertEquals(expected, NativeNetwork.preview(network, SOURCE, 1000)) } }
        assertEquals(2, expected.positions.size)
        println("Placement full conversion: 10 previews of 20000 tracks/signals in $elapsed")
    }

    private companion object {
        const val TRACK = 0x1000000000001L
        const val SOURCE = 0x8000000000001L
    }
}
