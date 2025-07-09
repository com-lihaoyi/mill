package mill.util.bsp

import mill.api.JsonFormatters.given
import mill.api.daemon.internal.bsp.BspMainModuleApi
import mill.api.daemon.internal.{EvaluatorApi, TaskApi, internal}
import mill.api.{Discover, Evaluator, ExternalModule, ModuleCtx}
import mill.util.MainModule
import mill.Task

import java.nio.file.Path

@internal
private[mill] object BspMainModule extends ExternalModule {

  // Requirement of ExternalModule's
  override protected def millDiscover: Discover = Discover[this.type]

  // Hack-ish way to have some BSP state in the module context
  @internal
  implicit class EmbeddableBspMainModule(mainModule: MainModule)
      extends mill.api.Module {
    // We act in the context of the module
    override def moduleCtx: ModuleCtx = mainModule.moduleCtx

    // We keep all BSP-related tasks/state in this sub-module
    @internal
    object internalBspMainModule extends mill.api.Module with BspMainModuleApi {

      private[mill] def bspClean(
          evaluator: EvaluatorApi,
          tasks: String*
      ): TaskApi[Seq[java.nio.file.Path]] = Task.Anon {
        mainModule.cleanTask(evaluator.asInstanceOf[Evaluator], tasks*)().map(_.path.toNIO)
      }

    }
  }

}
