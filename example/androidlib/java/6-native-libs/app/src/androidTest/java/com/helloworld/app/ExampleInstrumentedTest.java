package com.helloworld.app;

import static org.junit.Assert.*;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {

  @Test
  public void textViewDisplaysHelloFromCpp() {
    // Launch the MainActivity using ActivityScenario
    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
      scenario.onActivity(activity -> {
        // Get the text from the TextView
        String actualText = activity.stringFromJNI();

        // Assert that the text matches "Hello from C++"
        assertEquals("Hello from C++", actualText);
      });
    }
  }
}
