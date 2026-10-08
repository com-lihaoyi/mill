package com.example.lib

import org.scalatest.funsuite.AnyFunSuite

class SumTests extends AnyFunSuite {
  test("add") {
    assert(Sum.add(2, 2) == 4)
    assert(Sum.add(7, 3) == 10)
  }
}
