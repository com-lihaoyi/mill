package com.example.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class GreetingTest {
    @Test
    fun greetMentionsPlatform() {
        assertEquals("Hello, ${getPlatform().name}!", Greeting().greet())
    }
}
