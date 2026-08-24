package com.helloworld.logictest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helloworld.SampleLogicInKotlinDir
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SampleLogicInKotlinDirInstrumentedTest {
    @Test
    fun kotlin_dir_text_size_is_correct() {
        assertEquals(64f, SampleLogicInKotlinDir.textSize(), 0.00001f)
    }
}
