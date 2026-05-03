package com.example.app

import utest.*

object CalculatorTests extends TestSuite {
  val tests = Tests {
    test("add") {
      assert(Calculator.add(5, 5) == 10)
      assert(Calculator.add(-1, 1) == 0)
    }
  }
}
