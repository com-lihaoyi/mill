package mill.androidlib

import mill.T
import mill.define.PathRef

trait AndroidLibKotlinModule extends AndroidLibModule with AndroidKotlinModule { outer =>

  override def sources: T[Seq[PathRef]] =
    super[AndroidLibModule].sources() :+ PathRef(moduleDir / "src/main/kotlin")

  trait AndroidLibKotlinTests extends AndroidLibTests with KotlinTests {
    override def sources: T[Seq[PathRef]] =
      super[AndroidLibTests].sources() ++ Seq(PathRef(outer.moduleDir / "src/test/kotlin"))
  }
}
