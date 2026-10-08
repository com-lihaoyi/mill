import org.junit._

class MainTests {

  @Test def vmNameContainsJs(): Unit = {
    assert(
      Main.vmName.contains("js")
    )
  }

  @Test def vmNameContainsScala(): Unit = {
    assert(
      Main.vmName.contains("Scala")
    )
  }

}
