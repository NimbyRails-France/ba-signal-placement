package nimby.mod

import nimby.*
import fr.nimby.placement.tool.*

/** No signal catalogue and no mandatory dependency on SFR. Any signalling mod
 * can expose repeat.v1 in its own panel using the optional action declaration. */
fun createMod(): ToolMod {
    val placement = PlacementWorkflow()
    return toolMod("signal-placement", "Signal Placement") {
        service("repeat.v1") { request -> placement.click(request, SdkPlacementPort(this)) }
        onTick { placement.tick(SdkPlacementPort(this)) }
        onStop { placement.reset() }
    }
}
