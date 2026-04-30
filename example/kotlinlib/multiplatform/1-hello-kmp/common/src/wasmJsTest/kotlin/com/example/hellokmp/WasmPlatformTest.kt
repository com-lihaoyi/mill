package com.example.hellokmp

import kotlin.test.Test
import kotlin.test.assertEquals

class WasmPlatformTest {
    @Test
    fun testPlatformName() {
        assertEquals("web", getPlatformName())
    }
}
