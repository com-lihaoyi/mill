package mill.androidlib

import mill.androidlib.bsp.BspAndroidScalaModule
import mill.androidlib.idea.GenIdeaAndroidScalaModule
import mill.api.ModuleRef
import mill.scalalib.ScalaModule

trait AndroidScalaModule extends ScalaModule with AndroidModule {

  override private[mill] lazy val bspExt = {
    ModuleRef(new BspAndroidScalaModule.Wrap(this) {}.internalBspJavaModule)
  }

  private[mill] override lazy val genIdeaInternalExt = {
    ModuleRef(new GenIdeaAndroidScalaModule.Wrap(this) {}.internalGenIdea)
  }

}
