package fr.nimby.placement.tool

import kotlin.test.*
import nimby.*

private const val TRACK=0x1000000000001L
private const val SOURCE=0x8000000000001L
private fun event(action: String="repeat",world: String="world",generation: Long=1) =
    SignalActionRequest(1,SOURCE,action,"repeat.v1",world,generation,10,"repeat")

class PlacementWorkflowTest {
    @Test fun typedSpacingValidatesAndRecalculatesBeforeConstruction() {
        val w=PlacementWorkflow();val p=Port();w.click(event("preview"),p)
        assertEquals(5,p.buttons.size);assertEquals(1000,p.inputs.single().value)
        w.click(event("spacing").copy(value=725),p)
        assertEquals(725,p.inputs.single().value);assertTrue(p.enabled("apply"))
        assertEquals(4,p.ghosts.size);assertEquals(0,p.creations)
        for(value in listOf<Int?>(null,0,2,100001)){
            w.click(event("spacing").copy(value=value),p);assertEquals(725,p.inputs.single().value);assertFalse(p.enabled("apply"))
        }
        w.click(event("preview"),p);w.click(event("apply"),p)
        assertEquals(1,p.creations);assertEquals(4,p.createdPositions.size)
        assertEquals(825.0/3100,p.createdPositions.first().fraction,1e-9)
    }
    private class Port : PlacementPort {
        override var worldId="world"
        override var generation=1L
        var length=3100.0
        var creations=0;var preparations=0;var undos=0;var polls=0
        var createdPositions=emptyList<SignalPosition>()
        var pendingAnswer=false;var throwCreate=false
        var ghosts=emptyList<SignalPosition>();var previewFailure=false;var previewCalls=0
        var pollAnswer=ConstructionState.Applied
        var changeOnPrepare=false
        var message="";var buttons=emptyList<ToolButton>();var inputs=emptyList<ToolNumberInput>()
        override fun network()=ToolNetwork(worldId,generation,listOf(ToolTrack(TRACK,null,null,length)),
            emptyList(),listOf(ToolSignal(SOURCE,TRACK,100.0/length,1,1)))
        private fun answer(state: ConstructionState)=ConstructionResult(state,51,
            if(state==ConstructionState.Applied)listOf(SOURCE+1) else emptyList(),0,state==ConstructionState.Applied)
        override fun prepare(source: Long): ConstructionResult { preparations++;if(changeOnPrepare)length=2100.0;return answer(ConstructionState.Ready) }
        override fun create(ticket: Long,source: Long,positions: List<SignalPosition>): ConstructionResult {
            assertEquals(51,ticket);creations++;createdPositions=positions
            if(throwCreate)error("transport interrompu")
            return answer(if(pendingAnswer)ConstructionState.Pending else ConstructionState.Applied)
        }
        override fun undo(ticket: Long): ConstructionResult { undos++;return answer(ConstructionState.Undone) }
        override fun poll(ticket: Long): ConstructionResult { polls++;return answer(pollAnswer) }
        override fun showPreview(request: SignalActionRequest,positions: List<SignalPosition>) {
            if(previewFailure)error("rendu indisponible")
            previewCalls++;ghosts=positions
        }
        override fun clearPreview() { ghosts=emptyList() }
        override fun panel(request: SignalActionRequest,message: String,buttons: List<ToolButton>,inputs: List<ToolNumberInput>) { this.message=message;this.buttons=buttons;this.inputs=inputs }
        fun enabled(id: String)=buttons.single { it.id==id }.enabled
    }
    @Test fun previewExplicitApplyAndUndo() {
        val w=PlacementWorkflow();val p=Port()
        w.click(event(),p);assertEquals(0,p.preparations);assertFalse(p.enabled("apply"))
        w.click(event("apply"),p);assertEquals(0,p.creations)
        w.click(event("preview"),p);assertEquals(0,p.preparations);assertTrue(p.enabled("apply"))
        assertEquals(2,p.ghosts.size)
        assertTrue(p.message.contains("preview.unknown.many"));assertTrue(p.message.contains("1000 m, 2000 m"))
        assertTrue(p.message.contains("\"count\":\"2\"")) // Translation is resolved by the resident renderer.
        w.click(event("apply"),p);assertEquals(1,p.creations);assertEquals(2,p.createdPositions.size)
        assertTrue(p.ghosts.isEmpty())
        w.click(event("apply"),p);assertEquals(1,p.creations)
        assertTrue(p.enabled("undo"));w.click(event("undo"),p);assertEquals(1,p.undos);assertFalse(p.enabled("undo"))
    }
    @Test fun changedNetworkRequiresAnotherConfirmation() {
        val w=PlacementWorkflow();val p=Port();w.click(event("preview"),p)
        p.changeOnPrepare=true;w.click(event("apply"),p)
        assertEquals(0,p.creations);assertTrue(p.message == tr("changed"));assertTrue(p.enabled("apply"))
        w.click(event("apply"),p);assertEquals(1,p.creations);assertEquals(1,p.createdPositions.size)
    }
    @Test fun uncertainCreateIsPolledNeverRetried() {
        for(throws in listOf(false,true)) {
            val w=PlacementWorkflow();val p=Port();p.pendingAnswer=true;p.throwCreate=throws
            w.click(event("preview"),p);w.click(event("apply"),p)
            assertFalse(p.enabled("apply"));assertFalse(p.enabled("preview"))
            w.click(event("apply"),p);w.click(event("preview"),p);assertEquals(1,p.creations);assertEquals(1,p.preparations)
            w.tick(p);assertEquals(1,p.polls);assertEquals(1,p.creations);assertTrue(p.enabled("undo"))
        }
    }
    @Test fun changedSessionAndSpacingInvalidatePreview() {
        val w=PlacementWorkflow();val p=Port();w.click(event("preview"),p)
        w.click(event("spacing").copy(value=900),p);assertTrue(p.enabled("apply"));assertTrue(p.message.contains("900 m"))
        w.click(event("preview"),p);p.generation=2;w.click(event("apply"),p);assertEquals(0,p.creations)
        w.tick(p);w.click(event("apply",generation=2),p);assertEquals(0,p.creations)
        w.click(event("preview",generation=2),p);w.click(event("apply",generation=2),p);assertEquals(1,p.creations)
    }
    @Test fun nativeDirectionAndJunctionAreRespected() {
        val network=ToolNetwork("world",1,listOf(ToolTrack(TRACK,null,null,4000.0)),
            listOf(ToolJunction(TRACK+1,TRACK,.6,1,1)),listOf(ToolSignal(SOURCE,TRACK,.025,-1,4)))
        val preview=NativeNetwork.preview(network,SOURCE,1000)
        assertEquals(2,preview.positions.size);assertTrue(preview.positions.all { it.direction == -1 })
        assertEquals(fr.nimby.placement.StopReason.JUNCTION,preview.plan.stop)
        assertEquals(.275,preview.positions.first().fraction,1e-12)
    }
    @Test fun hidingRefreshAndRendererFailureNeverConstruct() {
        val w=PlacementWorkflow();val p=Port();w.click(event("preview"),p)
        val shown=p.ghosts;val calls=p.previewCalls
        w.tick(p);assertEquals(calls+1,p.previewCalls);assertEquals(shown,p.ghosts)
        w.click(event("hide-preview"),p);assertTrue(p.ghosts.isEmpty());assertFalse(p.enabled("apply"))
        w.tick(p);assertTrue(p.ghosts.isEmpty());assertEquals(0,p.creations)
        p.previewFailure=true;w.click(event("preview"),p)
        assertFalse(p.enabled("apply"));assertTrue(p.ghosts.isEmpty());assertTrue(p.message == tr("failed"))
        p.previewFailure=false;w.click(event("preview"),p);assertTrue(p.ghosts.isNotEmpty())
        p.previewFailure=true;w.tick(p)
        assertFalse(p.enabled("apply"));assertTrue(p.ghosts.isEmpty());assertEquals(0,p.creations)
    }
    @Test fun sessionChangeRemovesGhosts() {
        val w=PlacementWorkflow();val p=Port();w.click(event("preview"),p)
        p.generation++;w.tick(p);assertTrue(p.ghosts.isEmpty());assertEquals(0,p.creations)
    }
    @Test fun closeHidesAllControlsAndPreviewUntilExplicitReopen() {
        val w=PlacementWorkflow();val p=Port();w.click(event("preview"),p)
        w.click(event("spacing").copy(value=725),p)
        w.click(event("close"),p)
        assertEquals(listOf(ToolButton("repeat",tr("repeat"))),p.buttons)
        assertTrue(p.message.isEmpty()&&p.inputs.isEmpty()&&p.ghosts.isEmpty())
        val published=p.previewCalls
        w.tick(p);w.click(event("apply"),p)
        assertEquals(published,p.previewCalls);assertEquals(0,p.creations)
        assertEquals(1,p.buttons.size)
        w.click(event(),p)
        assertEquals(725,p.inputs.single().value);assertFalse(p.enabled("apply"))
        assertEquals(5,p.buttons.size);assertTrue(p.ghosts.isEmpty())
    }
    @Test fun closeRetainsPendingTicketAndUndoOnReopen() {
        val w=PlacementWorkflow();val p=Port();p.pendingAnswer=true
        w.click(event("preview"),p);w.click(event("apply"),p)
        assertTrue(p.enabled("close"));w.click(event("close"),p)
        w.tick(p);assertEquals(1,p.polls);assertEquals(1,p.buttons.size)
        assertEquals(1,p.creations);assertEquals(0,p.undos)
        w.click(event(),p);assertTrue(p.enabled("undo"))
        w.click(event("undo"),p);assertEquals(1,p.undos)
    }
}
