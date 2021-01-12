package hellotest

import hello._
import org.junit.Test

class MainTests {

  @Test
  def vmNameContainsNative() = {
    assertTrue(
      Main.vmName.contains("Native")
    )
  }
  @Test
  def vmNameContainsScala() = {
    assertTrue(
      Main.vmName.contains("Scala")
    )
  }
}
