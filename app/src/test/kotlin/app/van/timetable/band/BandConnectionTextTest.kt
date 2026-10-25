package app.van.timetable.band

import org.junit.Assert.*
import org.junit.Test

class BandConnectionTextTest {
    @Test fun connectedWithoutPermissionDoesNotClaimDisconnected() {
        val text = BandConnectionText.ready("小米手环10 Pro", false, null)
        assertTrue(text.contains("设备已连接"))
        assertTrue(text.contains("尚未授权"))
        assertFalse(text.contains("未连接"))
    }

    @Test fun missingWristAppAndReadyChannelHaveDifferentNextSteps() {
        assertTrue(BandConnectionText.ready("手环", true, false).contains("安装同套 RPK"))
        val ready = BandConnectionText.ready("手环", true, true)
        assertTrue(ready.contains("可验证通信"))
        assertFalse(ready.contains("保存"))
    }
}
