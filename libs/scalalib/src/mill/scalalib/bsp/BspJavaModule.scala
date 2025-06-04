package mill.scalalib.bsp

import java.nio.file.Path

import mill.Task
import mill.api.internal.{
  BspJavaModuleApi,
  EvaluatorApi,
  SemanticDbJavaModuleApi,
  UnresolvedPathApi
}
import mill.define.{Discover, ExternalModule, ModuleCtx}
import mill.scalalib.JavaModule

object BspJavaModule extends ExternalModule {

  // Requirement of ExternalModule's
  override protected def millDiscover: Discover = Discover[this.type]

  //  implicit def toEmbeddableBspJavaModule(jm: JavaModuleApi & BspModuleApi)
  //      : EmbeddableBspJavaModule = jm.match {
  //    case jm: EmbeddableBspJavaModule => jm
  //    case m => new EmbeddableBspJavaModule(m)
  //  }

  // Hack-ish way to have some BSP state in the module context
  implicit class EmbeddableBspJavaModule(jm: JavaModule & BspModule)
      extends mill.define.Module {
    // We act in the context of the module
    override def moduleCtx: ModuleCtx = jm.moduleCtx

    // We keep all BSP-related tasks/state in this sub-module
    object internalBspJavaModule extends mill.define.Module with BspJavaModuleApi {

//      def bspBuildTargetJavacOptions(clientWantsSemanticDb: Boolean)
//          : Task[EvaluatorApi => (
//              classesPath: Path,
//              javacOptions: Seq[String],
//              classpath: Seq[String]
//          )] = {
//        val classesPathTask = jm match {
//          case sem: SemanticDbJavaModuleApi if clientWantsSemanticDb =>
//            sem.bspCompiledClassesAndSemanticDbFiles
//          case _ => jm.bspCompileClassesPath
//        }
//        Task.Anon { (ev: EvaluatorApi) =>
//          (
//            classesPath =
//              classesPathTask().asInstanceOf[UnresolvedPathApi[os.Path]].resolve(os.Path(
//                ev.outPathJava
//              )).toNIO,
//            javacOptions = jm.javacOptions() ++ jm.mandatoryJavacOptions.apply(),
//            classpath = jm.bspCompileClasspath.apply().apply(ev)
//          )
//        }
//      }

      private[mill] def bspBuildTargetJavacOptions(
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
            jm.javacOptions() ++ km.mandatoryJavacOptions(),
            jm.bspCompileClasspath(needsToMergeResourcesIntoCompileDest).apply().apply(ev)
          )
        }
      }
    }
  }

}
