package com.helloworld.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 */
@RunWith(classOf[AndroidJUnit4])
class ExampleInstrumentedTest {
  @Test
  def useAppContext(): Unit = {
    // Context of the app under test.
    val appContext = InstrumentationRegistry.getInstrumentation.getTargetContext
    assertEquals("com.helloworld.app", appContext.getPackageName)
  }

  @Test
  def checkHelloString(): Unit = {
    val appContext = InstrumentationRegistry.getInstrumentation.getTargetContext
    val hello = appContext.getString(R.string.hello_scala)
    assertEquals("Hello from Scala 3 on Android!", hello)
  }

  @Test
  def checkSkiaSize(): Unit = {
    val appContext = InstrumentationRegistry.getInstrumentation.getTargetContext
    val size = appContext.getResources.getDimension(R.dimen.skia_size)
    assertTrue(size > 0)
  }
}
