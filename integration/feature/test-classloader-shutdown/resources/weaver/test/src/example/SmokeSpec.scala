package example

import cats.effect.IO
import weaver.*

object SmokeSpec extends SimpleIOSuite {
  (1 to 200).foreach { index =>
    test(s"trivial-$index") {
      IO.pure(expect(true))
    }
  }
}
