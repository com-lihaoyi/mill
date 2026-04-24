package mill.androidlib

import mill.{T, Task}
import mill.api.PathRef

trait AndroidLibScalaModule extends AndroidLibModule with AndroidScalaModule { outer =>

  trait AndroidLibScalaTests extends AndroidLibTests with ScalaTests {
    override def sources: T[Seq[PathRef]] =
      super[AndroidLibTests].sources() ++ Seq(PathRef(outer.moduleDir / "src/test/scala"))
  }
}
