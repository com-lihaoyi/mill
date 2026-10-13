package app

import org.junit.Assert.assertEquals
import org.junit.Test

class AdderTests {
  @Test def addsNumbers(): Unit = assertEquals(42, Adder.add(40, 2))
}
