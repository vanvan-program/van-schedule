package app.van.timetable.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetPinRequestTest {
    @Test
    fun unsupportedLauncherDoesNotReceiveRequest() {
        val result = requestWidgetPin({ false }) { error("Must not request pinning") }
        assertEquals(WidgetPinResult.UNSUPPORTED, result)
    }

    @Test
    fun rejectedRequestHasManualFallback() {
        assertEquals(WidgetPinResult.REJECTED, requestWidgetPin({ true }, { false }))
    }

    @Test
    fun acceptedRequestDoesNotClaimWidgetWasAdded() {
        val result = requestWidgetPin({ true }, { true })
        assertEquals(WidgetPinResult.REQUESTED, result)
        assertTrue(result.message.contains("若没有弹窗"))
        assertFalse(result.message.contains("添加成功"))
    }

    @Test
    fun supportCheckFailureHasManualFallback() {
        val result = requestWidgetPin({ throw SecurityException("blocked") }) {
            error("Must not request pinning")
        }
        assertEquals(WidgetPinResult.FAILED, result)
    }

    @Test
    fun requestFailureHasManualFallback() {
        val result = requestWidgetPin({ true }) { throw IllegalStateException("unavailable") }
        assertEquals(WidgetPinResult.FAILED, result)
    }
}
