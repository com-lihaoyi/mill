package mill.androidlib.bsp

import mill.api.daemon.internal.internal
import mill.api.{ModuleCtx, experimental}
import mill.androidlib.AndroidScalaModule
import mill.javalib.bsp.BspModule
import mill.scalalib.bsp.BspScalaModule

@experimental
trait BspAndroidScalaModule extends BspAndroidModule, BspScalaModule {

  def javaModuleRef: mill.api.ModuleRef[AndroidScalaModule & BspModule]

}

object BspAndroidScalaModule {
  trait Wrap(jm0: AndroidScalaModule & BspModule) extends mill.api.Module {
    override def moduleCtx: ModuleCtx = jm0.moduleCtx
    override protected[mill] implicit def moduleNestedCtx: ModuleCtx.Nested = jm0.moduleNestedCtx
    @internal
    object internalBspJavaModule extends BspAndroidScalaModule {
      private[mill] def isScript = jm0.isScript
      def javaModuleRef = mill.api.ModuleRef(jm0)
    }
  }

}
