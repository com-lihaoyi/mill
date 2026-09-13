package com.helloworld.logictest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helloworld.SampleLogic
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SampleLogicInstrumentedTest {
    @Test
    fun text_size_is_correct() {
        assertEquals(32f, SampleLogic.textSize(), 0.0001f)
    }
}
