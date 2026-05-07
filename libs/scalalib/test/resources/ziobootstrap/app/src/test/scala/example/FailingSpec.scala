package example

import zio._
import zio.test._

object FailingSpec extends ZIOSpec[String] {

  override val bootstrap: ZLayer[Any, Any, String] = ZLayer.scoped {
    ZIO.acquireRelease {
      ZIO.fail(new RuntimeException("Layer initialization failed"))
    } { _ => ZIO.unit }
  }

  def spec = suite("Test")(
    test("uses environment") {
      ZIO.service[String].map(s => assertTrue(s.nonEmpty))
    }
  )
}
