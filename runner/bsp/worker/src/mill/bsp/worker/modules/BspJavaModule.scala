package mill.bsp.worker.modules

import mill.Task
import mill.api.internal.{BspModuleApi, EvaluatorApi, JavaModuleApi, SemanticDbJavaModuleApi, UnresolvedPathApi}
import mill.define.{Discover, ExternalModule, ModuleCtx}
import mill.scalalib.{JavaModule, SemanticDbJavaModule}

object BspJavaModule extends ExternalModule {
  // Requirement of ExternalModule's
  override protected def millDiscover: Discover = Discover[this.type]

  // Hack-ish way to have some BSP state in the module context
  implicit class EmbeddableBspJavaModule(jm: JavaModuleApi & BspModuleApi) extends mill.define.Module {
    // We act in the context of the module
    override def moduleCtx: ModuleCtx = jm.moduleCtx

    // We keep all BSP-related tasks/state in this sub-module
    object bspJavaModule extends mill.define.Module {

      def bspBuildTargetJavacOptions(clientWantsSemanticDb: Boolean) = {
        val classesPathTask = jm match {
          case sem: SemanticDbJavaModuleApi if clientWantsSemanticDb =>
            sem.bspCompiledClassesAndSemanticDbFiles
          case _ => jm.bspCompileClassesPath
        }
        Task.Anon { (ev: EvaluatorApi) =>
          (
            classesPathTask().asInstanceOf[UnresolvedPathApi[os.Path]].resolve(os.Path(ev.outPathJava)).toNIO,
            jm.javacOptions() ++ jm.mandatoryJavacOptions.apply(),
            jm.bspCompileClasspath.apply().apply(ev)
          )
        }
      }
    }
  }

}
