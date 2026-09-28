@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package fr.nimby.placement.tool

import kotlinx.cinterop.*
import kotlin.test.*
import nimby.internal.*
import nimby.ToolContext

private var panels=0
private var spacing=0L
private fun callback(op: Int,integers: CPointer<LongVar>?,count: Int,numbers: CPointer<DoubleVar>?,numberCount: Int,text: CPointer<ByteVar>?,textSize: Int): Int {
    if(op==7) return 0
    if(op==9&&count==3&&integers!=null&&integers[2]==0L&&numberCount==0)return 0
    if(op!=8 || integers==null || count!=13 || numbers!=null || numberCount!=0 || text==null || textSize<=0) return 1
    if(integers[0]!=10L || integers[1]!=0x8000000000001L || integers[2]!=5L||integers[3]!=1L) return 1
    spacing=integers[9];panels++;return 0
}
class ToolBridgeTest {
    @Test fun nativeCallbackRoundTripAndExpiredContext() {
        assertEquals(3,version());assertEquals(1,modKind());assertEquals(1,serviceCount())
        assertEquals(0,toolStop());panels=0
        memScoped {
            assertEquals(0,serviceEvent(0,1,0x8000000000001L,1,10,"repeat".cstr.ptr,"world".cstr.ptr,"repeat".cstr.ptr,staticCFunction(::callback)))
        }
        assertEquals(1,panels);assertEquals(1000L,spacing)
        memScoped {
            assertEquals(0,serviceEventV2(0,2,0x8000000000001L,1,10,"spacing".cstr.ptr,"world".cstr.ptr,"repeat".cstr.ptr,staticCFunction(::callback),1,725))
        }
        assertEquals(2,panels);assertEquals(725L,spacing);assertEquals(0,toolStop())
        lateinit var retained: ToolContext
        ToolAccess.withContext("world",1,{_,_,_,_->0}) { retained=it }
        assertFailsWith<IllegalStateException> { retained.network() }
    }
    @Test fun previewBridgeCopiesPositionsAndRejectsInvalidOrRetainedContext() {
        lateinit var retained: ToolContext
        var calls=0
        ToolAccess.withContext("world",1,{op,ints,nums,text ->
            assertEquals(9,op);calls++
            if(calls==1){
                assertContentEquals(longArrayOf(10,0x8000000000001L,3,
                    0x1000000000001L,-1,0x1000000010001L,1,0x1000000020001L,-1),ints)
                assertContentEquals(doubleArrayOf(.25,.5,.75),nums)
                assertEquals("repeat.v1\u0000repeat\u0000",text.decodeToString())
            }else{assertContentEquals(longArrayOf(0,0,0),ints);assertTrue(nums.isEmpty());assertTrue(text.isEmpty())}
            0
        }) { context ->
            retained=context
            val request=nimby.SignalActionRequest(1,0x8000000000001L,"preview","repeat.v1","world",1,10,"repeat")
            context.showSignalPreview(request,listOf(
                nimby.SignalPosition(0x1000000000001L,.25,-1),
                nimby.SignalPosition(0x1000000010001L,.5,1),
                nimby.SignalPosition(0x1000000020001L,.75,-1)))
            assertFailsWith<IllegalArgumentException> { context.showSignalPreview(request,listOf(nimby.SignalPosition(0x1000000000001L,Double.NaN,1))) }
            assertFailsWith<IllegalArgumentException> { context.showSignalPreview(request.copy(generation=2),emptyList()) }
            context.clearSignalPreview()
        }
        assertEquals(2,calls);assertFailsWith<IllegalStateException> { retained.clearSignalPreview() }
    }
}
