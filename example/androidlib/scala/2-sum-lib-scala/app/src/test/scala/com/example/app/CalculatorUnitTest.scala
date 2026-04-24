package com.example.app

import org.junit.Test
import org.junit.Assert._

class CalculatorUnitTest {
  @Test
  def testAdd(): Unit = {
    assertEquals(10, Calculator.add(7, 3))
  }
}
