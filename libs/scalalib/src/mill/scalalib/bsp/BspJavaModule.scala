package mill.scalalib.bsp

import java.nio.file.Path

import mill.Task
import mill.api.internal.{BspJavaModuleApi, EvaluatorApi}
import mill.define.{Discover, ExternalModule, ModuleCtx}
import mill.scalalib.{JavaModule, SemanticDbJavaModule}

object BspJavaModule extends ExternalModule {

  // Requirement of ExternalModule's
  override protected def millDiscover: Discover = Discover[this.type]

  // Hack-ish way to have some BSP state in the module context
  implicit class EmbeddableBspJavaModule(jm: JavaModule & BspModule)
      extends mill.define.Module {
    // We act in the context of the module
    override def moduleCtx: ModuleCtx = jm.moduleCtx

    // We keep all BSP-related tasks/state in this sub-module
    object internalBspJavaModule extends mill.define.Module with BspJavaModuleApi {

      override def bspBuildTargetJavacOptions(
          needsToMergeResourcesIntoCompileDest: Boolean,
          clientWantsSemanticDb: Boolean
      ): Task[EvaluatorApi => (
          classesPath: Path,
          javacOptions: Seq[String],
          classpath: Seq[String]
      )] = {
        val classesPathTask = jm match {
          case sem: SemanticDbJavaModule if clientWantsSemanticDb =>
            sem.bspCompiledClassesAndSemanticDbFiles
          case m => m.bspCompileClassesPath(needsToMergeResourcesIntoCompileDest)
        }
        Task.Anon { (ev: EvaluatorApi) =>
          (
            classesPathTask().resolve(os.Path(ev.outPathJava)).toNIO,
            jm.javacOptions() ++ jm.mandatoryJavacOptions(),
            jm.bspCompileClasspath(needsToMergeResourcesIntoCompileDest).apply().apply(ev)
          )
        }
      }
    }
  }

}
