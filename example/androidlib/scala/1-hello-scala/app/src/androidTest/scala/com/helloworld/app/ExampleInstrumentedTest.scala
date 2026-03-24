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
  def checkAppName(): Unit = {
    val appContext = InstrumentationRegistry.getInstrumentation.getTargetContext
    val appName = appContext.getString(R.string.app_name)
    assertEquals("HelloWorldAppScala", appName)
  }
}
