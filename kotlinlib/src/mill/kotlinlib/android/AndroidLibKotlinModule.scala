package mill.kotlinlib.android
import mill.{Agg, T, Task}
import mill.api.PathRef
import mill.javalib.android.AndroidLibModule
import mill.kotlinlib.{DepSyntax, KotlinModule}

trait AndroidLibKotlinModule extends AndroidLibModule with AndroidKotlinModule { outer =>
  
  override def sources: T[Seq[PathRef]] =
    super[AndroidLibModule].sources() :+ PathRef(moduleDir / "src/main/kotlin")
  
  trait AndroidLibKotlinTests extends AndroidLibTests with KotlinTests {
    override def sources: T[Seq[PathRef]] =
      super[AndroidLibTests].sources() ++ Seq(PathRef(outer.moduleDir / "src/test/kotlin"))
  }
}
