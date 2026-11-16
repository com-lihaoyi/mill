package com.example.shared

import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidPlatformTest {
    @Test
    fun platformIsAndroid() {
        assertTrue(getPlatform().name.startsWith("Android"))
    }
}
