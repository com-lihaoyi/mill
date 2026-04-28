package mill.androidlib

import mill.{T, Task}
import mill.api.PathRef

trait AndroidLibKotlinModule extends AndroidLibModule with AndroidKotlinModule { outer =>

  trait AndroidLibKotlinTests extends AndroidLibTests with KotlinTests {

    def kotlinTestSources: T[Seq[PathRef]] = Task.Sources(outer.moduleDir / "src/test/kotlin")

    override def sources: T[Seq[PathRef]] =
      super[AndroidLibTests].sources() ++ kotlinTestSources()
  }
}
