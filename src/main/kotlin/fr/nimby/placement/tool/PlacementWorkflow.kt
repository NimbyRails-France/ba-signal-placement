package fr.nimby.placement.tool

import fr.nimby.placement.StopReason
import nimby.*

/** A small port keeps recipes independent of the game and of native pointers. */
interface PlacementPort {
    val worldId: String
    val generation: Long
    fun network(): ToolNetwork
    fun prepare(source: Long): ConstructionResult
    fun create(ticket: Long, source: Long, positions: List<SignalPosition>): ConstructionResult
    fun undo(ticket: Long): ConstructionResult
    fun poll(ticket: Long): ConstructionResult
    fun showPreview(request: SignalActionRequest, positions: List<SignalPosition>)
    fun clearPreview()
    fun panel(request: SignalActionRequest, message: String, buttons: List<ToolButton>, inputs: List<ToolNumberInput> = emptyList())
    fun log(message: String, level: LogLevel = LogLevel.Info) {}
}

class SdkPlacementPort(private val context: ToolContext) : PlacementPort {
    override val worldId get() = context.worldId
    override val generation get() = context.generation
    override fun network() = context.network()
    override fun prepare(source: Long) = context.prepareConstruction(source)
    override fun create(ticket: Long, source: Long, positions: List<SignalPosition>) = context.createSignals(ticket,source,positions)
    override fun undo(ticket: Long) = context.undoConstruction(ticket)
    override fun poll(ticket: Long) = context.pollConstruction(ticket)
    override fun showPreview(request: SignalActionRequest, positions: List<SignalPosition>) = context.showSignalPreview(request,positions)
    override fun clearPreview() = context.clearSignalPreview()
    override fun panel(request: SignalActionRequest, message: String, buttons: List<ToolButton>, inputs: List<ToolNumberInput>) = context.showPanel(request,message,buttons,inputs)
    override fun log(message: String, level: LogLevel) = context.log(message,level)
}

/** One explicit operation at a time. CREATE and UNDO are submitted once. A
 * pending/uncertain answer is polled using its original ticket, never resubmitted.
 * All methods run serially on the SDK worker; the UI only queues intentions. */
class PlacementWorkflow {
    private var request: SignalActionRequest? = null
    private var spacing = 1000
    private var preview: NativeNetwork.Preview? = null
    private var result: ConstructionResult? = null
    private var pending = false
    private var panelOpen = false
    private var message = tr("choose")
    private data class Panel(val message: String,val buttons: List<ToolButton>,val inputs: List<ToolNumberInput>)
    private var rendered: Panel? = null

    fun reset() { request=null;preview=null;result=null;pending=false;panelOpen=false;rendered=null;message=tr("choose") }
    private fun sameSession(r: SignalActionRequest,p: PlacementPort) = r.worldId==p.worldId && r.generation==p.generation

    fun click(event: SignalActionRequest, port: PlacementPort) {
        if(!sameSession(event,port)) return
        request?.let { if(!sameSession(it,port)) reset() }
        val old=request
        if(old != null && (old.panelToken != event.panelToken || old.signalId != event.signalId)) {
            // Do not abandon a native operation merely because another signal
            // was selected. Its original ticket remains the only recovery path.
            if(pending) {
                if(event.action=="close") collapsed(event,port)
                else port.panel(event,tr("busyOther"),listOf(
                    ToolButton(event.originAction,tr("retry")),ToolButton("close",tr("close"))))
                return
            }
            runCatching { port.clearPreview() };reset()
        }
        // A closed menu accepts only an explicit reopen. In particular, no
        // old confirmation may construct after closing the preview.
        if(!panelOpen&&event.action!=event.originAction&&request!=null)return
        request=event;panelOpen=true;rendered=null
        port.log("Signal Placement action=${event.action} source=${event.signalId} world=${event.worldId} generation=${event.generation} spacing=$spacing ticket=${result?.token}")
        try {
            when(event.action) {
                event.originAction -> Unit // Open/reopen without constructing anything.
                "close" -> {
                    // Closing is UI-only. Keep the native ticket (including an
                    // uncertain operation) and continue polling while hidden.
                    preview=null
                    runCatching { port.clearPreview() }.onFailure {
                        port.log("Preview clear on close failed: ${it.message}",LogLevel.Error)
                    }
                    collapsed(event,port);panelOpen=false;return
                }
                "preview" -> if(!pending) calculate(port)
                "apply" -> if(!pending && preview != null) apply(port)
                "undo" -> if(!pending && result?.canUndo==true) submit(port) { port.undo(result!!.token) }
                "hide-preview" -> if(!pending) {
                    port.clearPreview();preview=null;message=tr("hidden")
                }
                "spacing" -> if(!pending) {
                    // The native field is bounded too; validate again at the
                    // mod boundary before replacing the approved preview.
                    val value=requireNotNull(event.value) { "Saisir un espacement en mètres." }
                    require(value in 3..100_000) { "L'espacement doit être compris entre 3 et 100 000 m." }
                    val refresh=preview!=null
                    spacing=value;preview=null;port.clearPreview()
                    if(refresh) calculate(port)
                    else message=tr("spacing", "spacing" to spacing)
                }
            }
        } catch(error: Exception) {
            port.log("Signal Placement failure: ${error.stackTraceToString().take(900)}",LogLevel.Error)
            preview=null
            runCatching { port.clearPreview() }
            message=if(pending) tr("uncertain") else tr("failed")
        }
        render(port)
    }

    fun tick(port: PlacementPort) {
        val r=request ?: return
        if(!sameSession(r,port)) { runCatching { port.clearPreview() };reset();return }
        if(pending) {
            try { accept(port.poll(result!!.token)) }
            catch(_: Exception) { message=tr("uncertain") }
        }
        // The SDK owns an expiring drawing lease. Refresh only the approved
        // positions; stopping the worker cannot leave an orphan map preview.
        preview?.let {
            try { port.showPreview(r,it.positions) }
            catch(error: Exception) {
                preview=null;runCatching { port.clearPreview() }
                message=tr("previewUnavailable")
                port.log("Preview renderer failure: ${error.stackTraceToString().take(900)}",LogLevel.Error)
            }
        }
        render(port)
    }

    private fun calculate(port: PlacementPort) {
        preview=null
        port.clearPreview()
        val network=port.network()
        require(network.worldId==port.worldId && network.generation==port.generation) { "La partie a changé" }
        val next=NativeNetwork.preview(network,request!!.signalId,spacing)
        // Failure to publish is a real failure: never enable Confirm against
        // a numerical-only result when the requested map preview is missing.
        port.showPreview(request!!,next.positions)
        preview=next
        val stop=when(next.plan.stop) {
            StopReason.JUNCTION -> "junction";StopReason.END_OF_TRACK -> "end"
            StopReason.CYCLE -> "cycle";StopReason.CANDIDATE_LIMIT -> "limit"
            StopReason.TRACK_LIMIT -> "trackLimit";else -> "unknown"
        }
        val count=next.positions.size
        val distances=next.plan.placements.take(3).joinToString(", ") { "${it.distanceFromSourceM.toInt()} m" }
        message=if(count==0) tr("preview.$stop.empty", "skipped" to next.plan.skipped.size)
            else tr("preview.$stop.${if(count==1) "one" else "many"}",
                "count" to count, "distances" to (distances + if(count>3) "…" else ""))
        port.log("Placement preview source=${request!!.signalId} spacing=$spacing count=$count skipped=${next.plan.skipped.size} coveredM=${next.plan.coveredDistanceM} stop=${next.plan.stop}")
    }

    private fun apply(port: PlacementPort) {
        val shown=preview ?: return
        if(shown.positions.isEmpty()) return
        // Preparing establishes a native command fence. Capture AFTER it, then
        // compare to the approved preview; any discrepancy requires a new click.
        result=port.prepare(request!!.signalId)
        require(result!!.state==ConstructionState.Ready && result!!.token!=0L) { "Préparation refusée (code ${result!!.reason})" }
        calculate(port)
        if(preview != shown) { message=tr("changed");return }
        submit(port) { port.create(result!!.token,request!!.signalId,shown.positions) }
    }

    private fun submit(port: PlacementPort, command: () -> ConstructionResult) {
        port.clearPreview()
        pending=true;preview=null // Set BEFORE calling: transport failure may hide a real write.
        accept(command())
    }
    private fun accept(answer: ConstructionResult) {
        require(result==null || answer.token==result!!.token) { "Réponse d'un autre ticket" }
        result=answer;pending=answer.state==ConstructionState.Pending
        message=when(answer.state) {
            ConstructionState.Applied -> tr("applied", "count" to answer.createdIds.size)
            ConstructionState.Undone -> tr("undone")
            ConstructionState.Partial -> tr("partial", "count" to answer.createdIds.size)
            ConstructionState.Rejected -> tr("rejected", "reason" to answer.reason)
            ConstructionState.Pending -> tr("pending")
            ConstructionState.Ready -> tr("ready")
        }
    }
    private fun render(port: PlacementPort) {
        if(!panelOpen)return
        val r=request ?: return
        val buttons=listOf(
            ToolButton("preview",tr("preview"),!pending),
            ToolButton("apply",tr("apply"),!pending&&preview?.positions?.isNotEmpty()==true),
            ToolButton("hide-preview",tr("hide"),!pending&&preview!=null),
            ToolButton("undo",tr("undo"),!pending&&result?.canUndo==true),
            ToolButton("close",tr("close")))
        val next=Panel(message,buttons,
            listOf(ToolNumberInput("spacing",tr("spacing.label"),spacing,3,100_000,!pending)))
        if(rendered!=next) {
            port.log("Signal Placement source=${r.signalId} ticket=${result?.token} state=${result?.state} pending=$pending preview=${preview?.positions?.size}")
        }
        // Restore the worker's acknowledged model after a suspended capture.
        // The SDK deduplicates identical publications without cancelling clicks.
        port.panel(r,next.message,next.buttons,next.inputs);rendered=next
    }
    // Reuse the declared action ID so reopening follows the normal service
    // callback. No spacing field, summary or confirmation remains displayed.
    private fun collapsed(event: SignalActionRequest,port: PlacementPort) {
        port.panel(event,"",listOf(ToolButton(event.originAction,tr("repeat"))))
    }
}
