package mill.androidlib.bsp

import mill.api.daemon.internal.internal
import mill.api.{ModuleCtx, experimental}
import mill.androidlib.AndroidModule
import mill.javalib.bsp.BspModule

@experimental
trait BspAndroidModule extends mill.javalib.bsp.BspJavaModule {

  def javaModuleRef: mill.api.ModuleRef[AndroidModule & BspModule]
}

object BspAndroidModule {
  trait Wrap(jm0: AndroidModule & BspModule) extends mill.api.Module {
    override def moduleCtx: ModuleCtx = jm0.moduleCtx
    override protected[mill] implicit def moduleNestedCtx: ModuleCtx.Nested = jm0.moduleNestedCtx
    @internal
    object internalBspJavaModule extends BspAndroidModule {
      private[mill] def isScript = jm0.isScript
      def javaModuleRef = mill.api.ModuleRef(jm0)
    }
  }

}
