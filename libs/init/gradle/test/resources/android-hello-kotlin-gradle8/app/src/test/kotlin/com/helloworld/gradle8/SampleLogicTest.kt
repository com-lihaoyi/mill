package com.helloworld.gradle8

import org.junit.Assert.*
import org.junit.Test

class SampleLogicTest {
    @Test
    fun text_size_is_correct() {
        assertEquals(32f, SampleLogic.textSize())
    }
}
