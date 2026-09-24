package com.example.hellokmp

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformTest {
    @Test
    fun testPlatformNameIsNotEmpty() {
        assertTrue(getPlatformName().isNotEmpty(), "Platform name should not be empty")
    }
}
