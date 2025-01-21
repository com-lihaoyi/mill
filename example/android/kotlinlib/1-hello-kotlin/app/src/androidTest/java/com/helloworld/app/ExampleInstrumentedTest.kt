package com.helloworld.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helloworld.SampleLogic
import com.helloworld.SampleLogicInKotlinDir
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.helloworld.app", appContext.packageName)
        assertEquals(32f, SampleLogic.textSize(), 0.0001f)
        assertEquals(64f, SampleLogicInKotlinDir.textSize(), 0.00001f)
    }
}
