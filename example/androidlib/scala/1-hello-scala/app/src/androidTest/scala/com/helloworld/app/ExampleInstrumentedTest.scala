package com.helloworld.app

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.{matches, doesNotExist}
import androidx.test.espresso.matcher.ViewMatchers.{withText, isDisplayed}
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(classOf[AndroidJUnit4])
class ExampleInstrumentedTest {

  @Test
  def testActivityContent(): Unit = {
    // Launch the activity
    val scenario = ActivityScenario.launch(classOf[MainActivity])

    // Check if the TextView displays the message parsed from the JSON resource
    onView(withText("Hello from Scala with Gson!")).check(matches(isDisplayed()))

    // Check that a non-existent text does not exist in the view hierarchy
    onView(withText("Non-existent text")).check(doesNotExist())

    scenario.close()
  }

  @Test
  def useAppContext(): Unit = {
    val appContext = InstrumentationRegistry.getInstrumentation.getTargetContext
    assertEquals("com.helloworld.app", appContext.getPackageName)
  }
}
