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
        var captures=0;var previewStatus=0;var failPreviewOnPrepare=false
        var clearStatus=0;var panelStatus=0;var networkStatus=0
        var clearCalls=0;var panelCalls=0;var failClearAt=0
        var busyAfterPrepare=false;var busyPanelAfterCreate=false
        var prepareStatus=0;var createStatus=0;var undoStatus=0;var pollStatus=0;var logStatus=0
        var busyPanelAfterUndo=false
        var undoAnswer=ConstructionState.Undone
        val errors=mutableListOf<String>()
        var createdPositions=emptyList<SignalPosition>()
        var pendingAnswer=false;var throwCreate=false
        var ghosts=emptyList<SignalPosition>();var previewFailure=false;var previewCalls=0
        var pollAnswer=ConstructionState.Applied
        var changeOnPrepare=false
        var message="";var buttons=emptyList<ToolButton>();var inputs=emptyList<ToolNumberInput>()
        override fun network(): ToolNetwork {
            captures++
            if(networkStatus!=0)throw ToolOperationException(networkStatus,"injected read refusal")
            return ToolNetwork(worldId,generation,listOf(ToolTrack(TRACK,null,null,length)),
                emptyList(),listOf(ToolSignal(SOURCE,TRACK,100.0/length,1,1)))
        }
        private fun answer(state: ConstructionState)=ConstructionResult(state,51,
            if(state==ConstructionState.Applied)listOf(SOURCE+1) else emptyList(),0,state==ConstructionState.Applied)
        override fun prepare(source: Long): ConstructionResult {
            preparations++;if(changeOnPrepare)length=2100.0
            if(prepareStatus!=0)throw ToolOperationException(prepareStatus,"injected prepare refusal")
            if(failPreviewOnPrepare)previewStatus=12
            if(busyAfterPrepare){clearStatus=12;panelStatus=12}
            return answer(ConstructionState.Ready)
        }
        override fun create(ticket: Long,source: Long,positions: List<SignalPosition>): ConstructionResult {
            assertEquals(51,ticket);creations++;createdPositions=positions
            if(busyPanelAfterCreate)panelStatus=12
            if(createStatus!=0)throw ToolOperationException(createStatus,"injected create uncertainty")
            if(throwCreate)error("transport interrompu")
            return answer(if(pendingAnswer)ConstructionState.Pending else ConstructionState.Applied)
        }
        override fun undo(ticket: Long): ConstructionResult {
            assertEquals(51,ticket);undos++
            if(busyPanelAfterUndo)panelStatus=12
            if(undoStatus!=0)throw ToolOperationException(undoStatus,"injected undo uncertainty")
            return answer(undoAnswer)
        }
        override fun poll(ticket: Long): ConstructionResult {
            assertEquals(51,ticket);polls++
            if(pollStatus!=0)throw ToolOperationException(pollStatus,"injected poll refusal")
            return answer(pollAnswer)
        }
        override fun showPreview(request: SignalActionRequest,positions: List<SignalPosition>) {
            previewCalls++
            if(previewStatus!=0)throw ToolOperationException(previewStatus,"injected preview refusal")
            if(previewFailure)error("rendu indisponible")
            ghosts=positions
        }
        override fun clearPreview() {
            clearCalls++
            val status=if(clearCalls==failClearAt)12 else clearStatus
            if(status!=0)throw ToolOperationException(status,"injected clear refusal")
            ghosts=emptyList()
        }
        override fun panel(request: SignalActionRequest,message: String,buttons: List<ToolButton>,inputs: List<ToolNumberInput>) {
            panelCalls++
            if(panelStatus!=0)throw ToolOperationException(panelStatus,"injected panel refusal")
            this.message=message;this.buttons=buttons;this.inputs=inputs
        }
        override fun log(message: String,level: LogLevel) {
            if(logStatus!=0)throw ToolOperationException(logStatus,"injected diagnostic refusal")
            if(level==LogLevel.Error)errors+=message
        }
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
    @Test fun typedAdapterKeepsAllCollisionsAtTheSamePositionInObservedOrder() {
        val network=ToolNetwork("world",1,listOf(ToolTrack(TRACK,null,TRACK+1,1100.0),
            ToolTrack(TRACK+1,TRACK,null,2100.0)),emptyList(),listOf(
            ToolSignal(SOURCE,TRACK,100.0/1100,1,1),
            ToolSignal(SOURCE+1,TRACK+1,0.0,-1,1),
            ToolSignal(SOURCE+2,TRACK+1,0.0,1,1),
            ToolSignal(SOURCE+3,TRACK+1,1000.0/2100,1,1)))
        val preview=NativeNetwork.preview(network,SOURCE,1000)
        assertEquals(listOf(listOf(SOURCE+1,SOURCE+2),listOf(SOURCE+3)),
            preview.plan.skipped.map { it.existingSignalIds })
        assertEquals(1,preview.positions.size)
        assertEquals(TRACK+1,preview.positions.single().trackId)
        assertEquals(2000.0/2100,preview.positions.single().fraction,1e-12)
    }
    @Test fun typedTopologyPreservesTravelDirectionAcrossReversedEndsAndStopsAtCycles() {
        val reversed=ToolNetwork("world",1,listOf(ToolTrack(TRACK,null,TRACK+1,600.0),
            ToolTrack(TRACK+1,null,TRACK,1000.0)),emptyList(),listOf(ToolSignal(SOURCE,TRACK,1.0/6,-1,4)))
        val preview=NativeNetwork.preview(reversed,SOURCE,400)
        assertEquals(listOf(TRACK,TRACK+1,TRACK+1),preview.positions.map { it.trackId })
        assertEquals(listOf(-1,1,1),preview.positions.map { it.direction })
        assertEquals(5.0/6,preview.positions[0].fraction,1e-12)
        assertEquals(.7,preview.positions[1].fraction,1e-12)
        assertEquals(.3,preview.positions[2].fraction,1e-12)

        val cycle=ToolNetwork("world",1,listOf(ToolTrack(TRACK,TRACK+2,TRACK+1,1000.0),
            ToolTrack(TRACK+1,TRACK,TRACK+2,1000.0),ToolTrack(TRACK+2,TRACK+1,TRACK,1000.0)),
            emptyList(),listOf(ToolSignal(SOURCE,TRACK,.1,1,1)))
        val bounded=NativeNetwork.preview(cycle,SOURCE,1000)
        assertEquals(fr.nimby.placement.StopReason.CYCLE,bounded.plan.stop)
        assertEquals(listOf(TRACK+1,TRACK+2),bounded.positions.map { it.trackId })
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
    @Test fun busyPreviewRetainsPlanAndRetriesOncePerTickWithoutRecapturing() {
        val w=PlacementWorkflow();val p=Port();p.previewStatus=12
        w.click(event("preview"),p)
        assertEquals(1,p.previewCalls);assertEquals(1,p.captures)
        assertFalse(p.enabled("apply"));assertTrue(p.enabled("hide-preview"))
        assertEquals(tr("previewWaiting"),p.message)
        repeat(4) {
            w.click(event("apply"),p) // A stale queued confirmation cannot bypass the disabled button.
            w.tick(p)
        }
        assertEquals(5,p.previewCalls);assertEquals(1,p.captures)
        assertEquals(0,p.preparations);assertEquals(0,p.creations);assertTrue(p.ghosts.isEmpty())
        p.previewStatus=0;w.tick(p)
        assertEquals(6,p.previewCalls);assertEquals(1,p.captures)
        assertTrue(p.enabled("apply"));assertEquals(2,p.ghosts.size)
        assertTrue(p.message.contains("preview.unknown.many"))
        assertEquals(0,p.creations) // Recovery is not confirmation.
        w.click(event("apply"),p);assertEquals(1,p.creations)
    }
    @Test fun busyRenewalDoesNotClearLeaseButInvalidationAbandonsPlan() {
        val w=PlacementWorkflow();val p=Port();w.click(event("preview"),p)
        val shown=p.ghosts
        p.previewStatus=12;w.tick(p)
        assertEquals(shown,p.ghosts);assertFalse(p.enabled("apply"));assertEquals(1,p.captures)
        p.previewStatus=9;w.tick(p)
        assertTrue(p.ghosts.isEmpty());assertFalse(p.enabled("apply"));assertFalse(p.enabled("hide-preview"))
        val attempts=p.previewCalls;p.previewStatus=0;repeat(3){w.tick(p)}
        assertEquals(attempts,p.previewCalls);assertEquals(0,p.creations)
    }
    @Test fun busyDuringApplyRevalidationNeverCreatesAndRequiresAnotherClick() {
        val w=PlacementWorkflow();val p=Port();w.click(event("preview"),p)
        p.failPreviewOnPrepare=true;w.click(event("apply"),p)
        assertEquals(1,p.preparations);assertEquals(2,p.captures)
        assertEquals(0,p.creations);assertFalse(p.enabled("apply"))
        p.previewStatus=0;p.failPreviewOnPrepare=false;w.tick(p)
        assertTrue(p.enabled("apply"));assertEquals(0,p.creations)
        w.click(event("apply"),p);assertEquals(1,p.creations);assertEquals(2,p.preparations)
    }
    @Test fun closeOrSessionChangeDropsBusyCandidate() {
        for(close in listOf(false,true)) {
            val w=PlacementWorkflow();val p=Port();p.previewStatus=12;w.click(event("preview"),p)
            if(close)w.click(event("close"),p) else p.generation++
            p.previewStatus=0;repeat(3){w.tick(p)}
            assertEquals(1,p.previewCalls);assertEquals(0,p.creations);assertTrue(p.ghosts.isEmpty())
        }
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
    @Test fun clearAndPanelBusyAfterPrepareRecoverWithoutSubmittingOrLosingApproval() {
        val w=PlacementWorkflow();val p=Port();w.click(event("preview"),p)
        val shown=p.ghosts
        p.busyAfterPrepare=true;w.click(event("apply"),p)
        assertEquals(1,p.preparations);assertEquals(1,p.captures);assertEquals(0,p.creations)
        assertEquals(shown,p.ghosts);assertTrue(p.errors.isEmpty())
        val clearAttempts=p.clearCalls;val panelAttempts=p.panelCalls
        repeat(3) { w.tick(p) }
        assertEquals(clearAttempts+3,p.clearCalls);assertEquals(panelAttempts+3,p.panelCalls)
        assertEquals(1,p.captures);assertTrue(p.errors.isEmpty())
        w.click(event("apply"),p) // Previously enabled native controls may still have a queued click.
        assertEquals(1,p.preparations);assertEquals(0,p.creations)
        p.busyAfterPrepare=false;p.clearStatus=0;p.panelStatus=0;w.tick(p)
        assertEquals(2,p.captures);assertTrue(p.enabled("apply"));assertEquals(shown,p.ghosts)
        assertEquals(0,p.creations) // Only a new explicit confirmation may submit.
        w.click(event("apply"),p);assertEquals(1,p.creations);assertEquals(2,p.preparations)
    }
    @Test fun busyClearBeforeSubmitDoesNotQueueCreateOrUndo() {
        val w=PlacementWorkflow();val p=Port();w.click(event("preview"),p)
        p.failClearAt=p.clearCalls+2 // Revalidation clears first, submission clears second.
        w.click(event("apply"),p);assertEquals(0,p.creations);assertFalse(p.enabled("apply"))
        w.tick(p);assertTrue(p.enabled("apply"));assertEquals(0,p.creations)
        w.click(event("apply"),p);assertEquals(1,p.creations)
        p.clearStatus=12;w.click(event("undo"),p);assertEquals(0,p.undos)
        p.clearStatus=0;w.tick(p);assertEquals(0,p.undos)
        w.click(event("undo"),p);assertEquals(1,p.undos)
    }
    @Test fun busyHideAndCloseRevokeQueuedApplyBeforeCleanupAndPanelSucceed() {
        for(action in listOf("hide-preview","close")) {
            val w=PlacementWorkflow();val p=Port();w.click(event("preview"),p)
            p.clearStatus=12;p.panelStatus=12;w.click(event(action),p)
            val attempts=p.clearCalls;repeat(2){w.tick(p)}
            assertEquals(attempts+2,p.clearCalls)
            w.click(event("apply"),p);assertEquals(0,p.creations);assertEquals(0,p.preparations)
            p.clearStatus=0;p.panelStatus=0;w.tick(p)
            assertTrue(p.ghosts.isEmpty());assertTrue(p.errors.isEmpty())
            if(action=="close")assertEquals(listOf("repeat"),p.buttons.map { it.id })
            else { assertFalse(p.enabled("apply"));assertEquals(tr("hidden"),p.message) }
        }
    }
    @Test fun busyPanelAfterCreateKeepsOriginalTicketAndPollsWithoutResubmission() {
        val w=PlacementWorkflow();val p=Port();p.pendingAnswer=true;p.busyPanelAfterCreate=true
        w.click(event("preview"),p);w.click(event("apply"),p)
        assertEquals(1,p.creations);assertEquals(0,p.polls)
        w.tick(p);assertEquals(1,p.polls);assertEquals(1,p.creations)
        w.click(event("undo"),p);assertEquals(0,p.undos) // No acknowledged recovery panel yet.
        p.panelStatus=0;w.tick(p)
        assertEquals(1,p.polls);assertTrue(p.enabled("undo"));assertTrue(p.errors.isEmpty())
        w.click(event("undo"),p);assertEquals(1,p.undos)
    }
    @Test fun busyNetworkReadRetriesOncePerTickAndInvalidClearDoesNotRetry() {
        val w=PlacementWorkflow();val p=Port();p.networkStatus=12
        w.click(event("preview"),p);assertEquals(1,p.captures);assertFalse(p.enabled("apply"))
        w.tick(p);assertEquals(2,p.captures);assertEquals(0,p.preparations)
        p.networkStatus=0;w.tick(p);assertEquals(3,p.captures);assertTrue(p.enabled("apply"))
        p.clearStatus=9;w.click(event("apply"),p)
        assertEquals(0,p.creations);assertFalse(p.enabled("apply"));assertTrue(p.errors.isNotEmpty())
        val attempts=p.clearCalls;p.clearStatus=0;repeat(2){w.tick(p)}
        assertEquals(attempts,p.clearCalls);assertFalse(p.enabled("apply"))
    }
    @Test fun reopenCancelsDeferredCollapsedPanelAndSessionChangeDropsDeferredWork() {
        val w=PlacementWorkflow();val p=Port();w.click(event("preview"),p)
        p.clearStatus=12;p.panelStatus=12;w.click(event("close"),p)
        w.click(event(),p) // Reopen before either old presentation can complete.
        p.clearStatus=0;p.panelStatus=0;w.tick(p)
        assertEquals(5,p.buttons.size);assertFalse(p.enabled("apply"));assertTrue(p.ghosts.isEmpty())
        w.tick(p);assertEquals(5,p.buttons.size)
        w.click(event("preview"),p);p.busyAfterPrepare=true;w.click(event("apply"),p)
        p.generation++;w.tick(p) // No retained callback/context may cross this boundary.
        val reads=p.captures;val drawings=p.previewCalls;val clears=p.clearCalls;val panels=p.panelCalls
        p.clearStatus=0;p.panelStatus=0;repeat(3){w.tick(p)}
        assertEquals(reads,p.captures);assertEquals(drawings,p.previewCalls)
        assertEquals(clears,p.clearCalls);assertEquals(panels,p.panelCalls);assertEquals(0,p.creations)
    }
    @Test fun typedBusyPrepareNeedsFreshPreviewAndNewClickWhileBusyCommandsOnlyPoll() {
        val w=PlacementWorkflow();val p=Port();w.click(event("preview"),p)
        p.prepareStatus=12;w.click(event("apply"),p)
        assertEquals(1,p.preparations);assertEquals(0,p.creations);assertFalse(p.enabled("apply"))
        p.prepareStatus=0;w.tick(p);assertTrue(p.enabled("apply"));assertEquals(1,p.preparations)
        p.createStatus=12;p.busyPanelAfterCreate=true;w.click(event("apply"),p)
        assertEquals(1,p.creations);assertEquals(2,p.preparations)
        p.pollStatus=12;repeat(2){w.tick(p)}
        assertEquals(2,p.polls);assertEquals(1,p.creations)
        p.pollStatus=0;p.panelStatus=0;w.tick(p)
        assertEquals(3,p.polls);assertEquals(1,p.creations);assertTrue(p.enabled("undo"))
        assertTrue(p.errors.isEmpty())
    }
    @Test fun typedBusyOrPendingUndoKeepsTicketThroughPanelFailureWithoutSecondUndo() {
        for(status in listOf(0,12)) {
            val w=PlacementWorkflow();val p=Port();w.click(event("preview"),p);w.click(event("apply"),p)
            p.undoStatus=status;p.undoAnswer=ConstructionState.Pending;p.busyPanelAfterUndo=true
            w.click(event("undo"),p);assertEquals(1,p.undos)
            w.click(event("undo"),p);assertEquals(1,p.undos)
            p.pollAnswer=ConstructionState.Undone;w.tick(p)
            assertEquals(1,p.polls);assertEquals(1,p.undos)
            p.panelStatus=0;w.tick(p);assertFalse(p.enabled("undo"));assertEquals(1,p.undos)
            assertTrue(p.errors.isEmpty())
        }
    }
    @Test fun refusedDiagnosticNeverChangesPresentationOrCommandState() {
        val w=PlacementWorkflow();val p=Port();p.logStatus=12
        w.click(event("preview"),p);assertTrue(p.enabled("apply"))
        p.pendingAnswer=true;w.click(event("apply"),p);assertEquals(1,p.creations)
        w.tick(p);assertEquals(1,p.polls);assertTrue(p.enabled("undo"))
        w.click(event("undo"),p);assertEquals(1,p.undos)
    }
}
