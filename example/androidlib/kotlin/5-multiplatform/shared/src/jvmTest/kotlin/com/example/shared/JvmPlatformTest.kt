package com.example.shared

import kotlin.test.Test
import kotlin.test.assertTrue

class JvmPlatformTest {
    @Test
    fun platformIsJava() {
        assertTrue(getPlatform().name.startsWith("Java"))
    }
}
