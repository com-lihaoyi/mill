package app

import org.junit.Assert.{assertEquals, assertNull}
import org.junit.Test

class LibTests {
  @Test def some(): Unit = assertEquals("mill", Lib.nameOrNull(Some("mill")))
  @Test def none(): Unit = assertNull(Lib.nameOrNull(None))
  @Test def inTest(): Unit = assertEquals("js", Option("js").orNull)
}
