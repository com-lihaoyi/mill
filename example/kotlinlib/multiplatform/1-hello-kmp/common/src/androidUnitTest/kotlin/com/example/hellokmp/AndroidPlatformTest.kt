package com.example.hellokmp

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidPlatformTest {
    @Test
    fun testPlatformName() {
        assertEquals("android", getPlatformName())
    }
}
