package com.helloworld

import com.helloworld.app.MainActivity
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  def testCalculateRectArea(): Unit = {
    // 80.0 * 80.0 = 6400.0
    val area = MainActivity.calculateRectArea(80f, 80f)
    assertEquals(6400f, area, 0.0f)
  }
}
