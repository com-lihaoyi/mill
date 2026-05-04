package com.example.app

import org.scalatest.funsuite.AnyFunSuite

class CalculatorTests extends AnyFunSuite {
  test("add") {
    assert(Calculator.add(5, 5) == 10)
    assert(Calculator.add(-1, 1) == 0)
  }
}
