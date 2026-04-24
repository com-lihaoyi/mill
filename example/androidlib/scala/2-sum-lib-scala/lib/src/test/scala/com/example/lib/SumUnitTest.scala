package com.example.lib

import org.junit.Test
import org.junit.Assert._

class SumUnitTest {
  @Test
  def testAdd(): Unit = {
    assertEquals(4, Sum.add(2, 2))
  }
}
