package mill.scalalib.bsp
import mill.scalalib.ScalaModule
import java.nio.file.Path
import mill.api.daemon.internal.bsp.BspJavaModuleApi
import mill.Task
import mill.api.daemon.internal.{EvaluatorApi, TaskApi, internal}
import mill.api.{Discover, ExternalModule, ModuleCtx}
import mill.javalib.{JavaModule, SemanticDbJavaModule}
import mill.api.JsonFormatters.given
import mill.javalib.bsp.BspModule

trait BspScalaModule extends mill.javalib.bsp.BspJavaModule {

  def javaModuleRef: mill.api.ModuleRef[ScalaModule & BspModule]
  override def scalacOptionsTask = javaModuleRef().allScalacOptions
}

object BspScalaModule {
  trait Wrap(jm0: ScalaModule & BspModule) extends mill.api.Module {
    override def moduleCtx: ModuleCtx = jm0.moduleCtx

    @internal
    object internalBspJavaModule extends BspScalaModule {
      def javaModuleRef = mill.api.ModuleRef(jm0)
    }
  }
}
