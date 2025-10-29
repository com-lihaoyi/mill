package mill.scalalib.bsp
import mill.scalalib.ScalaModule
import mill.Task
import mill.api.daemon.internal.internal
import mill.api.ModuleCtx
import mill.javalib.bsp.BspModule

trait BspScalaModule extends mill.javalib.bsp.BspJavaModule {

  def javaModuleRef: mill.api.ModuleRef[ScalaModule & BspModule]
  override def scalacOptionsTask = javaModuleRef().allScalacOptions
}

object BspScalaModule {
  trait Wrap(jm0: ScalaModule & BspModule) extends mill.api.Module {
    override def moduleCtx: ModuleCtx = jm0.moduleCtx
    override protected[mill] implicit def moduleNestedCtx: ModuleCtx.Nested = jm0.moduleNestedCtx
    @internal
    object internalBspJavaModule extends BspScalaModule {
      def javaModuleRef = mill.api.ModuleRef(jm0)
      def isScript = jm0.isScript
    }
  }
}
