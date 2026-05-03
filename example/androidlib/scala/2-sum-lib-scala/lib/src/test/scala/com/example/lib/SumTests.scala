package com.example.lib

import utest.*

object SumTests extends TestSuite {
  val tests = Tests {
    test("add") {
      assert(Sum.add(2, 2) == 4)
      assert(Sum.add(7, 3) == 10)
    }
  }
}
