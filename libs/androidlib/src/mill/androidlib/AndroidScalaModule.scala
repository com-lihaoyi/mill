package mill.androidlib

import mill.androidlib.bsp.BspAndroidScalaModule
import mill.androidlib.idea.GenIdeaAndroidScalaModule
import mill.api.Task.Simple as T
import mill.api.{ModuleRef, PathRef, Task}
import mill.scalalib.ScalaModule

trait AndroidScalaModule extends ScalaModule with AndroidModule {

  override private[mill] lazy val bspExt = {
    ModuleRef(new BspAndroidScalaModule.Wrap(this) {}.internalBspJavaModule)
  }

  private[mill] override lazy val genIdeaInternalExt = {
    ModuleRef(new GenIdeaAndroidScalaModule.Wrap(this) {}.internalGenIdea)
  }

  private def scalaSources = Task.Sources("src/main/scala")

  override def sources: T[Seq[PathRef]] =
    super.sources() ++ scalaSources()

  trait AndroidScalaTestModule extends ScalaTests, AndroidTestModule, AndroidScalaModule {
    private def scalaSources = Task.Sources("src/test/scala")

    override def sources: T[Seq[PathRef]] =
      super[AndroidTestModule].sources() ++ scalaSources()
  }

}
