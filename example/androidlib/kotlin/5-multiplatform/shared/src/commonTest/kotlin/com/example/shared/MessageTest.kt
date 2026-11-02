package com.example.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class MessageTest {
    @Test
    fun jsonRoundTrip() {
        val message = Message(Greeting().greet(), getPlatform().name)
        assertEquals(message, messageFromJson(message.toJson()))
    }
}
