package mill.util

import utest.*

object HexFormatTests extends TestSuite {
  override def tests: Tests = Tests {
    test("bytesToHex") {
      assert(HexFormat.bytesToHex("hello".getBytes) == "68656c6c6f")
      assert(HexFormat.bytesToHex("hello world".getBytes) == "68656c6c6f20776f726c64")
    }
  }
}
