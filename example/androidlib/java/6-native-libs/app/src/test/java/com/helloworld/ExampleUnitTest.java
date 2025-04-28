package com.helloworld;

import static org.junit.Assert.*;

import com.helloworld.app.R;
//import com.helloworld.app.*;
import org.junit.Test;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest {
  @Test
  public void textSize_isCorrect() {

    assertEquals(32f, SampleLogic.textSize(), 0.000001f);
    assertEquals(0x7f010000, R.color.text_green);
//    assertEquals("Hello from cpp", MainActivity.stringFromJNI());
  }
}
