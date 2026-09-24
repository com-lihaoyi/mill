package com.example.hellokmp

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPlatformTest {
    @Test
    fun testPlatformName() {
        assertEquals("desktop", getPlatformName())
    }
}
