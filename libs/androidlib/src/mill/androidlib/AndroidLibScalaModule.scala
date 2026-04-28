package mill.androidlib

import mill.{T, Task}
import mill.api.PathRef

trait AndroidLibScalaModule extends AndroidLibModule with AndroidScalaModule { outer =>

  trait AndroidLibScalaTests extends AndroidLibTests with ScalaTests {

    def scalaTestSources: T[Seq[PathRef]] = Task.Sources(outer.moduleDir / "src/test/scala")

    override def sources: T[Seq[PathRef]] =
      super[AndroidLibTests].sources() ++ scalaTestSources()
  }
}
