package com.helloworld.logictest;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.helloworld.SampleLogic;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SampleLogicInstrumentedTest {
  @Test
  public void testTextSize() {
    float textSize = SampleLogic.textSize();
    assertEquals(32.0f, textSize, 0.0001f);
  }
}
