package com.helloworld.logictest;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.helloworld.SampleLogic;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AnotherLogicInstrumentedTest {
  @Test
  public void testTextSizeIsPositive() {
    float textSize = SampleLogic.textSize();
    assertTrue("Text size should be positive", textSize > 0);
  }
}
