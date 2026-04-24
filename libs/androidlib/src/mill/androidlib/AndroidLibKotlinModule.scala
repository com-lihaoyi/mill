package mill.androidlib

import mill.{T, Task}
import mill.api.PathRef

trait AndroidLibKotlinModule extends AndroidLibModule with AndroidKotlinModule { outer =>

  trait AndroidLibKotlinTests extends AndroidLibTests with KotlinTests {
    override def sources: T[Seq[PathRef]] =
      super[AndroidLibTests].sources() ++ Seq(PathRef(outer.moduleDir / "src/test/kotlin"))
  }
}
