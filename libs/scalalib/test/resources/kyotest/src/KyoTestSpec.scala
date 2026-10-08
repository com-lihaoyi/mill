package mill.scalalib

import kyo.*
import kyo.test.*

object HelloWorld {
  def sayHello: String = "Hello, World!"
}

class KyoTestSpec extends Test[Any] {
  "sayHello returns the expected greeting" in {
    assert(HelloWorld.sayHello == "Hello, World!")
  }
}
