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
    private var previewPublished = false
    private var previewMessage = ""
    private var refreshRequested = false
    private var clearRequested = false
    private var clearMessage: String? = null
    private var result: ConstructionResult? = null
    private var pending = false
    private var panelOpen = false
    private var panelPublished = false
    private var collapsedPending = false
    private var message = tr("choose")
    private data class Panel(
        val message: String, val pending: Boolean, val hasPreview: Boolean,
        val canApply: Boolean, val canUndo: Boolean, val spacing: Int,
        val buttons: List<ToolButton>, val inputs: List<ToolNumberInput>,
    )
    private var rendered: Panel? = null
    private data class AuxiliaryPanel(val request: SignalActionRequest, val message: String, val buttons: List<ToolButton>)
    private var auxiliaryPanel: AuxiliaryPanel? = null

    fun reset() {
        request=null;preview=null;previewPublished=false;previewMessage="";refreshRequested=false;clearRequested=false;clearMessage=null
        result=null;pending=false;panelOpen=false;panelPublished=false;collapsedPending=false
        rendered=null;auxiliaryPanel=null;message=tr("choose")
    }
    private fun sameSession(r: SignalActionRequest,p: PlacementPort) = r.worldId==p.worldId && r.generation==p.generation

    fun click(event: SignalActionRequest, port: PlacementPort) {
        if(!sameSession(event,port)) return
        request?.let { if(!sameSession(it,port)) reset() }
        val old=request
        if(old != null && (old.panelToken != event.panelToken || old.signalId != event.signalId)) {
            // Do not abandon a native operation merely because another signal
            // was selected. Its original ticket remains the only recovery path.
            if(pending) {
                auxiliaryPanel=if(event.action=="close") AuxiliaryPanel(event,"",listOf(ToolButton(event.originAction,tr("repeat"))))
                    else AuxiliaryPanel(event,tr("busyOther"),listOf(
                        ToolButton(event.originAction,tr("retry")),ToolButton("close",tr("close"))))
                renderAuxiliary(port)
                return
            }
            runCatching { port.clearPreview() };reset()
        }
        // A closed menu accepts only an explicit reopen. In particular, no
        // old confirmation may construct after closing the preview.
        if(!panelOpen&&event.action!=event.originAction&&request!=null)return
        request=event;panelOpen=true;collapsedPending=false;auxiliaryPanel=null
        diagnostic(port,"BA Signal Placement action=${event.action} source=${event.signalId} world=${event.worldId} generation=${event.generation} spacing=$spacing ticket=${result?.token}")
        try {
            when(event.action) {
                event.originAction -> Unit // Open/reopen without constructing anything.
                "close" -> {
                    // Closing is UI-only. Keep the native ticket (including an
                    // uncertain operation) and continue polling while hidden.
                    preview=null;previewPublished=false;refreshRequested=false;clearMessage=null
                    // Revoke queued confirmations before either presentation
                    // call: refusing a clear/panel must not reopen this menu.
                    panelOpen=false;panelPublished=false;collapsedPending=true
                    clearPreview(port)
                }
                "preview" -> if(!pending) calculate(port)
                "apply" -> if(!pending && panelPublished && preview != null && previewPublished) apply(port)
                "undo" -> if(!pending && panelPublished && result?.canUndo==true) submit(port) { port.undo(result!!.token) }
                "hide-preview" -> if(!pending) {
                    preview=null;previewPublished=false;refreshRequested=false
                    clearMessage=tr("hidden")
                    if(!clearPreview(port))message=tr("previewWaiting")
                }
                "spacing" -> if(!pending) {
                    // The native field is bounded too; validate again at the
                    // mod boundary before replacing the approved preview.
                    val value=requireNotNull(event.value) { "Saisir un espacement en mètres." }
                    require(value in 3..100_000) { "L'espacement doit être compris entre 3 et 100 000 m." }
                    // A repeated field acknowledgement changes no parameter.
                    // Keep the approved drawing and avoid another full capture.
                    if(value != spacing) {
                        val refresh=preview!=null
                        spacing=value;preview=null;previewPublished=false
                        if(refresh) calculate(port)
                        else {
                            refreshRequested=false;clearMessage=tr("spacing", "spacing" to spacing)
                            if(!clearPreview(port))message=tr("previewWaiting")
                        }
                    }
                }
            }
        } catch(error: Exception) {
            if(error is ToolOperationException && error.isBusy) {
                // PREPARE has not submitted a command. CREATE/UNDO, however,
                // set pending before their call and keep that original ticket.
                // Neither case stores a command to execute on a later tick.
                previewPublished=false
                message=if(pending) tr("uncertain") else tr("previewWaiting")
            } else {
                diagnostic(port,"BA Signal Placement failure: ${error.stackTraceToString().take(900)}",LogLevel.Error)
                abandonPreview(port)
                message=if(pending) tr("uncertain") else tr("failed")
            }
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
        try {
            if(refreshRequested) calculate(port)
            else if(!clearRequested || clearPreview(port)) publishPreview(port)
        } catch(error: Exception) {
            abandonPreview(port)
            message=tr("previewUnavailable")
            diagnostic(port,"Preview renderer failure: ${error.stackTraceToString().take(900)}",LogLevel.Error)
        }
        if(auxiliaryPanel!=null){renderAuxiliary(port);return}
        render(port)
    }

    private fun calculate(port: PlacementPort) {
        refreshRequested=true;previewPublished=false;clearMessage=null
        // Clearing and reading are repeatable presentation work. A refusal
        // after PREPARE keeps this work for the next tick, never the command.
        if(!clearPreview(port)){message=tr("previewWaiting");return}
        preview=null
        val network=try { port.network() } catch(error: ToolOperationException) {
            if(!error.isBusy)throw error
            message=tr("previewWaiting");return
        }
        require(network.worldId==port.worldId && network.generation==port.generation) { "La partie a changé" }
        val next=NativeNetwork.preview(network,request!!.signalId,spacing)
        preview=next;refreshRequested=false
        val stop=when(next.plan.stop) {
            StopReason.JUNCTION -> "junction";StopReason.END_OF_TRACK -> "end"
            StopReason.CYCLE -> "cycle";StopReason.CANDIDATE_LIMIT -> "limit"
            StopReason.TRACK_LIMIT -> "trackLimit";else -> "unknown"
        }
        val count=next.positions.size
        val distances=next.plan.placements.take(3).joinToString(", ") { "${it.distanceFromSourceM.toInt()} m" }
        previewMessage=if(count==0) tr("preview.$stop.empty", "skipped" to next.plan.skipped.size)
            else tr("preview.$stop.${if(count==1) "one" else "many"}",
                "count" to count, "distances" to (distances + if(count>3) "…" else ""))
        diagnostic(port,"Placement preview source=${request!!.signalId} spacing=$spacing count=$count skipped=${next.plan.skipped.size} coveredM=${next.plan.coveredDistanceM} stop=${next.plan.stop}")
        publishPreview(port)
    }

    private fun clearPreview(port: PlacementPort): Boolean {
        previewPublished=false;clearRequested=true
        try {
            port.clearPreview();clearRequested=false
            clearMessage?.let { message=it };clearMessage=null
            return true
        }
        catch(error: Exception) {
            if(error is ToolOperationException && error.isBusy)return false
            clearRequested=false;clearMessage=null;throw error
        }
    }
    private fun abandonPreview(port: PlacementPort) {
        preview=null;previewPublished=false;refreshRequested=false;clearMessage=null
        runCatching { clearPreview(port) }
        // Only Busy leaves repeatable cleanup pending. Other failures are
        // definitive; the native drawing also has its own expiration lease.
    }

    private fun publishPreview(port: PlacementPort) {
        val approved=preview ?: return
        // Never let a queued Apply use a publication that just failed. A busy
        // renderer leaves the native lease unchanged; we keep only the copied
        // positions and make one fresh validation attempt at the next tick.
        previewPublished=false
        try {
            port.showPreview(request!!,approved.positions)
            previewPublished=true;message=previewMessage
        } catch(error: ToolOperationException) {
            if(!error.isBusy)throw error
            message=tr("previewWaiting")
        }
    }

    private fun apply(port: PlacementPort) {
        val shown=preview ?: return
        if(shown.positions.isEmpty()) return
        // Preparing establishes a native command fence. Capture AFTER it, then
        // compare to the approved preview; any discrepancy requires a new click.
        result=port.prepare(request!!.signalId)
        require(result!!.state==ConstructionState.Ready && result!!.token!=0L) { "Préparation refusée (code ${result!!.reason})" }
        calculate(port)
        if(!previewPublished)return
        if(preview != shown) { message=tr("changed");return }
        submit(port) { port.create(result!!.token,request!!.signalId,shown.positions) }
    }

    private fun submit(port: PlacementPort, command: () -> ConstructionResult) {
        clearMessage=null
        if(!clearPreview(port)){message=tr("previewWaiting");return}
        pending=true;preview=null // Set BEFORE calling: transport failure may hide a real write.
        accept(command())
    }
    private fun accept(answer: ConstructionResult) {
        require(result==null || answer.token==result!!.token) { "Réponse d'un autre ticket" }
        result=answer;pending=answer.state==ConstructionState.Pending
        message=when(answer.state) {
            ConstructionState.Applied -> tr(if(answer.createdIds.size==1) "applied.one" else "applied.many", "count" to answer.createdIds.size)
            ConstructionState.Undone -> tr("undone")
            ConstructionState.Partial -> tr("partial", "count" to answer.createdIds.size)
            ConstructionState.Rejected -> tr("rejected", "reason" to answer.reason)
            ConstructionState.Pending -> tr("pending")
            ConstructionState.Ready -> tr("ready")
        }
    }
    private fun render(port: PlacementPort) {
        val r=request ?: return
        if(!panelOpen) {
            if(collapsedPending)collapsedPending=!publishPanel(port,r,"",listOf(ToolButton(r.originAction,tr("repeat"))))
            return
        }
        val hasPreview=preview!=null
        val canApply=previewPublished && preview?.positions?.isNotEmpty()==true
        val canUndo=result?.canUndo==true
        var next=rendered
        // Translation references are resolved by the resident renderer. Keeping
        // these immutable controls does not freeze the language or skip leases.
        if(next==null || next.message!=message || next.pending!=pending ||
            next.hasPreview!=hasPreview || next.canApply!=canApply ||
            next.canUndo!=canUndo || next.spacing!=spacing) {
            next=Panel(message,pending,hasPreview,canApply,canUndo,spacing,listOf(
                ToolButton("preview",tr("preview"),!pending),
                ToolButton("apply",tr("apply"),!pending&&canApply),
                ToolButton("hide-preview",tr("hide"),!pending&&hasPreview),
                ToolButton("undo",tr("undo"),!pending&&canUndo),
                ToolButton("close",tr("close"))),
                listOf(ToolNumberInput("spacing",tr("spacing.label"),spacing,3,100_000,!pending)))
            diagnostic(port,"BA Signal Placement source=${r.signalId} ticket=${result?.token} state=${result?.state} pending=$pending preview=${preview?.positions?.size}")
        }
        // Restore the worker's acknowledged model after a suspended capture.
        // The SDK deduplicates identical publications without cancelling clicks.
        rendered=next
        panelPublished=false
        panelPublished=publishPanel(port,r,next.message,next.buttons,next.inputs)
    }
    private fun publishPanel(port: PlacementPort,event: SignalActionRequest,message: String,
        buttons: List<ToolButton>,inputs: List<ToolNumberInput> = emptyList()): Boolean {
        try { port.panel(event,message,buttons,inputs);return true }
        catch(error: ToolOperationException) {
            if(!error.isBusy)throw error
            return false
        }
    }
    private fun renderAuxiliary(port: PlacementPort) {
        val panel=auxiliaryPanel ?: return
        if(!sameSession(panel.request,port) || publishPanel(port,panel.request,panel.message,panel.buttons))auxiliaryPanel=null
    }
    private fun diagnostic(port: PlacementPort,message: String,level: LogLevel = LogLevel.Info) {
        // Logging can itself meet the worker's resource budget. It must never
        // change an operation's state, lose its ticket, or trigger another log.
        runCatching { port.log(message,level) }
    }
}
