package hellotest

import hello._
import org.junit.Assert.assertTrue
import org.junit.Test

class MainTests {

  @Test
  def vmNameContainsNative(): Unit = {
    assertTrue(
      Main.vmName.contains("Native")
    )
  }
  @Test
  def vmNameContainsScala(): Unit = {
    assertTrue(
      Main.vmName.contains("Scala")
    )
  }
}
